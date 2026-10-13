package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.StorageFileMapper;
import com.star.mapper.UserMapper;
import com.star.mapper.VipConfigMapper;
import com.star.model.dto.ChunkUploadInitDTO;
import com.star.model.entity.StorageFile;
import com.star.model.entity.User;
import com.star.model.entity.VipConfig;
import com.star.model.vo.ChunkUploadCompleteVO;
import com.star.model.vo.ChunkUploadInitVO;
import com.star.service.IChunkUploadService;
import com.star.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 分片上传实现。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>断点续传的真相在磁盘</b>：已收到的分片以 {@code .chunks/{uploadId}/*.part} 为准，
 *       Redis 里的 Set 只是加速索引；会话过期甚至 Redis 重启后，重新 init 会扫描目录把进度恢复出来。</li>
 *   <li><b>秒传前移</b>：init 就带上整文件 MD5，服务端命中既有物理文件时直接建记录，
 *       客户端一个字节都不用传。</li>
 *   <li><b>服务端重新计算 MD5 做完整性校验</b>：合并后以服务端算出的 MD5 为准，
 *       与客户端声明不一致说明传输损坏，直接拒绝入库。</li>
 *   <li><b>目录归属校验</b>：分片目录内写 {@code .owner} 记录 userId，防止猜到 uploadId 后越权写入。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadServiceImpl implements IChunkUploadService {

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;
    private final VipConfigMapper vipConfigMapper;
    private final StorageFileMapper storageFileMapper;
    private final FileMapper fileMapper;
    private final OperationLogService operationLogService;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /** 会话 Hash：字段 userId / fileName / fileSize / chunkSize / totalChunks / folderId / fileMd5 */
    private static final String SESSION_PREFIX = "ld:upload:";
    private static final String RECEIVED_SUFFIX = ":received";
    private static final String ACTIVE_PREFIX = "ld:upload:active:";
    private static final String OWNER_FILE = ".owner";
    private static final String MERGED_FILE = "merged.tmp";

    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final long SESSION_TTL_HOURS = 24L;
    /** 单分片上限：防止前端传个几十 GB 的分片把内存打爆 */
    private static final long MAX_CHUNK_SIZE = 64L * 1024 * 1024;

    // ==================== init ====================

    @Override
    public ChunkUploadInitVO init(ChunkUploadInitDTO dto, Long userId) {
        if (dto == null) {
            throw new BusinessException("参数缺失");
        }
        String uploadId = requireUploadId(dto.getUploadId());
        String fileName = dto.getFileName() == null ? "" : dto.getFileName().trim();
        if (fileName.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        Long fileSize = dto.getFileSize();
        if (fileSize == null || fileSize <= 0) {
            throw new BusinessException("文件大小非法");
        }
        Long chunkSize = dto.getChunkSize();
        if (chunkSize == null || chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            throw new BusinessException("分片大小非法");
        }
        Integer totalChunks = dto.getTotalChunks();
        if (totalChunks == null || totalChunks <= 0) {
            throw new BusinessException("分片数量非法");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        VipConfig vipConfig = vipConfigMapper.selectById(user.getRole());
        if (vipConfig == null) {
            throw new BusinessException("用户等级配置不存在");
        }
        if (user.getUsedSpace() + fileSize > vipConfig.getMaxSpace()) {
            throw new BusinessException("存储空间不足");
        }

        // ---------- 秒传前移：MD5 已知且服务端已有 ----------
        String declaredMd5 = normalizeMd5(dto.getFileMd5());
        if (declaredMd5 != null) {
            StorageFile existing = storageFileMapper.selectOne(
                    new LambdaQueryWrapper<StorageFile>().eq(StorageFile::getFileMd5, declaredMd5));
            if (existing != null) {
                Long fileId = createUserFileRecord(user, fileName,
                        dto.getFolderId() == null ? 0L : dto.getFolderId(), existing, userId);
                log.info("分片上传-秒传命中: userId={}, fileName={}, md5={}", userId, fileName, declaredMd5);
                ChunkUploadInitVO vo = new ChunkUploadInitVO();
                vo.setUploadId(uploadId);
                vo.setInstant(true);
                vo.setFileId(fileId);
                vo.setChunkSize(chunkSize);
                vo.setTotalChunks(totalChunks);
                vo.setReceived(new ArrayList<Integer>());
                vo.setUploadedBytes(fileSize);
                return vo;
            }
        }

        // ---------- 建立 / 复用会话 ----------
        String sessionKey = SESSION_PREFIX + uploadId;
        Map<Object, Object> session = redis.opsForHash().entries(sessionKey);
        Path chunkDir = chunkDirOf(uploadId);

        if (session == null || session.isEmpty()) {
            int maxConcurrent = vipConfig.getMaxBatchUpload() == null ? 3 : vipConfig.getMaxBatchUpload();
            String activeKey = ACTIVE_PREFIX + userId;
            Long activeSize = redis.opsForSet().size(activeKey);
            Boolean alreadyActive = redis.opsForSet().isMember(activeKey, uploadId);
            if (!Boolean.TRUE.equals(alreadyActive) && activeSize != null && activeSize >= maxConcurrent) {
                throw new BusinessException("同时进行的上传任务过多（当前等级上限 " + maxConcurrent + " 个），请等待其他任务完成");
            }
            verifyOrClaimOwner(chunkDir, userId);

            Map<String, String> fields = new HashMap<>();
            fields.put("userId", String.valueOf(userId));
            fields.put("fileName", dto.getFileName());
            fields.put("fileSize", String.valueOf(fileSize));
            fields.put("chunkSize", String.valueOf(chunkSize));
            fields.put("totalChunks", String.valueOf(totalChunks));
            fields.put("folderId", String.valueOf(dto.getFolderId() == null ? 0L : dto.getFolderId()));
            fields.put("fileMd5", declaredMd5 == null ? "" : declaredMd5);
            redis.opsForHash().putAll(sessionKey, fields);
            redis.expire(sessionKey, SESSION_TTL_HOURS, TimeUnit.HOURS);
            redis.opsForSet().add(activeKey, uploadId);
            redis.expire(activeKey, SESSION_TTL_HOURS, TimeUnit.HOURS);
        } else {
            checkSessionOwner(session, userId);
            verifyOrClaimOwner(chunkDir, userId);
            redis.expire(sessionKey, SESSION_TTL_HOURS, TimeUnit.HOURS);
        }

        // ---------- 扫描磁盘恢复断点 ----------
        List<Integer> received = scanReceivedChunks(chunkDir, totalChunks, fileSize, chunkSize);
        String receivedKey = sessionKey + RECEIVED_SUFFIX;
        if (!received.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (Integer idx : received) {
                values.add(String.valueOf(idx));
            }
            redis.opsForSet().add(receivedKey, values.toArray(new String[0]));
        }
        redis.expire(receivedKey, SESSION_TTL_HOURS, TimeUnit.HOURS);

        long uploadedBytes = 0L;
        for (Integer idx : received) {
            uploadedBytes += chunkLengthOf(idx, totalChunks, fileSize, chunkSize);
        }

        ChunkUploadInitVO vo = new ChunkUploadInitVO();
        vo.setUploadId(uploadId);
        vo.setInstant(false);
        vo.setChunkSize(chunkSize);
        vo.setTotalChunks(totalChunks);
        vo.setReceived(received);
        vo.setUploadedBytes(uploadedBytes);
        log.info("分片上传-会话就绪: userId={}, uploadId={}, 已收分片={}/{}",
                userId, uploadId, received.size(), totalChunks);
        return vo;
    }

    // ==================== chunk ====================

    @Override
    public Map<String, Object> saveChunk(String uploadId, Integer index, MultipartFile chunk, Long userId) {
        String id = requireUploadId(uploadId);
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException("分片内容为空");
        }
        if (index == null || index < 0) {
            throw new BusinessException("分片序号非法");
        }

        String sessionKey = SESSION_PREFIX + id;
        Map<Object, Object> session = redis.opsForHash().entries(sessionKey);
        if (session == null || session.isEmpty()) {
            throw new BusinessException("上传会话已过期，请重新发起上传（已上传的分片不会丢失）");
        }
        checkSessionOwner(session, userId);

        int totalChunks = Integer.parseInt(String.valueOf(session.get("totalChunks")));
        long fileSize = Long.parseLong(String.valueOf(session.get("fileSize")));
        long chunkSize = Long.parseLong(String.valueOf(session.get("chunkSize")));
        if (index >= totalChunks) {
            throw new BusinessException("分片序号越界");
        }
        long expectBytes = chunkLengthOf(index, totalChunks, fileSize, chunkSize);
        if (chunk.getSize() != expectBytes) {
            throw new BusinessException("分片大小不符（期望 " + expectBytes + " 字节，实际 " + chunk.getSize() + "），请重传该分片");
        }

        Path chunkDir = chunkDirOf(id);
        verifyOrClaimOwner(chunkDir, userId);
        Path part = chunkDir.resolve(index + ".part");
        if (!Files.exists(part)) {
            // 先写临时文件再原子改名：即使写入中途断连，也不会留下被误认为「已完成」的半截分片
            Path tmp = chunkDir.resolve(index + ".part.tmp");
            try {
                chunk.transferTo(tmp.toAbsolutePath().toFile());
                moveQuietly(tmp, part);
            } catch (Exception e) {
                log.error("分片写入失败: uploadId={}, index={}", id, index, e);
                throw new BusinessException("分片写入失败，请重试");
            }
        }

        String receivedKey = sessionKey + RECEIVED_SUFFIX;
        redis.opsForSet().add(receivedKey, String.valueOf(index));
        redis.expire(receivedKey, SESSION_TTL_HOURS, TimeUnit.HOURS);
        redis.expire(sessionKey, SESSION_TTL_HOURS, TimeUnit.HOURS);

        Long count = redis.opsForSet().size(receivedKey);
        Map<String, Object> result = new HashMap<>();
        result.put("received", count == null ? 0 : count.intValue());
        result.put("total", totalChunks);
        return result;
    }

    // ==================== complete ====================

    @Override
    @Transactional
    public ChunkUploadCompleteVO complete(String uploadId, Long userId) {
        String id = requireUploadId(uploadId);
        String sessionKey = SESSION_PREFIX + id;
        Map<Object, Object> session = redis.opsForHash().entries(sessionKey);
        if (session == null || session.isEmpty()) {
            throw new BusinessException("上传会话已过期，请重新上传");
        }
        checkSessionOwner(session, userId);

        String fileName = String.valueOf(session.get("fileName"));
        Long fileSize = Long.valueOf(String.valueOf(session.get("fileSize")));
        Long chunkSize = Long.valueOf(String.valueOf(session.get("chunkSize")));
        int totalChunks = Integer.parseInt(String.valueOf(session.get("totalChunks")));
        Long folderId = Long.valueOf(String.valueOf(session.get("folderId")));
        String declaredMd5 = normalizeMd5(String.valueOf(session.get("fileMd5")));

        Path chunkDir = chunkDirOf(id);
        verifyOrClaimOwner(chunkDir, userId);

        // ---------- 1. 分片完整性 ----------
        List<Integer> received = scanReceivedChunks(chunkDir, totalChunks, fileSize, chunkSize);
        if (received.size() < totalChunks) {
            throw new BusinessException("分片不完整：已收到 " + received.size() + "/" + totalChunks + "，请继续上传");
        }
        for (Integer idx : received) {
            Path part = chunkDir.resolve(idx + ".part");
            long expect = chunkLengthOf(idx, totalChunks, fileSize, chunkSize);
            long actual;
            try {
                actual = Files.size(part);
            } catch (IOException e) {
                throw new BusinessException("分片读取失败，请重试");
            }
            if (actual != expect) {
                deleteQuietly(part);
                throw new BusinessException("分片 " + idx + " 校验失败（大小不符），请重新上传该分片");
            }
        }

        // ---------- 2. 顺序合并，同时增量计算 MD5（省掉合并后再全量读一遍盘） ----------
        Path merged = chunkDir.resolve(MERGED_FILE);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("MD5 算法不可用");
        }
        long mergedSize = 0L;
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(merged,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), 256 * 1024)) {
            for (int i = 0; i < totalChunks; i++) {
                try (InputStream in = Files.newInputStream(chunkDir.resolve(i + ".part"))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        mergedSize += read;
                    }
                }
            }
        } catch (IOException e) {
            log.error("分片合并失败: uploadId={}", id, e);
            throw new BusinessException("分片合并失败，请重试");
        }

        // ---------- 3. 完整性校验（服务端算出的 MD5 才是权威值） ----------
        final String md5 = toHex(digest.digest());
        if (declaredMd5 != null && !declaredMd5.equalsIgnoreCase(md5)) {
            deleteQuietly(merged);
            log.warn("分片上传-MD5 不一致: uploadId={}, declared={}, actual={}", id, declaredMd5, md5);
            throw new BusinessException("文件完整性校验失败（内容与声明不一致），请重新上传");
        }

        // ---------- 4. 配额复核 ----------
        User user = userMapper.selectById(userId);
        VipConfig vipConfig = user == null ? null : vipConfigMapper.selectById(user.getRole());
        if (user == null || vipConfig == null) {
            deleteQuietly(merged);
            throw new BusinessException("用户或等级配置不存在");
        }
        if (user.getUsedSpace() + mergedSize > vipConfig.getMaxSpace()) {
            deleteQuietly(merged);
            throw new BusinessException("存储空间不足");
        }

        // ---------- 5. 秒传复用 / 落盘新建 ----------
        StorageFile exist = storageFileMapper.selectOne(
                new LambdaQueryWrapper<StorageFile>().eq(StorageFile::getFileMd5, md5));
        boolean instant = exist != null;
        Long storageId;
        if (instant) {
            exist.setRefCount((exist.getRefCount() == null ? 0 : exist.getRefCount()) + 1);
            storageFileMapper.updateById(exist);
            storageId = exist.getId();
            deleteQuietly(merged);
        } else {
            String sub = md5.substring(0, 2);
            Path target = Paths.get(uploadDir, sub, md5).toAbsolutePath();
            try {
                Files.createDirectories(target.getParent());
                Files.move(merged, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("文件落盘失败: uploadId={}", id, e);
                throw new BusinessException("文件落盘失败，请重试");
            }
            StorageFile storageFile = new StorageFile();
            storageFile.setFileMd5(md5);
            storageFile.setRealPath(target.toString());
            storageFile.setFileSize(mergedSize);
            storageFile.setRefCount(1);
            storageFile.setFirstUploadUser(userId);
            storageFile.setFileExtension(extOf(fileName));
            storageFileMapper.insert(storageFile);
            storageId = storageFile.getId();
        }

        // ---------- 6. 建立用户文件记录 ----------
        com.star.model.entity.File userFile = new com.star.model.entity.File();
        userFile.setFileName(fileName);
        userFile.setStorageId(storageId);
        userFile.setUserId(userId);
        userFile.setFolderId(folderId);
        userFile.setDownloadCount(0);
        userFile.setStatus(1);
        fileMapper.insert(userFile);

        user.setUsedSpace(user.getUsedSpace() + mergedSize);
        userMapper.updateById(user);

        operationLogService.record(userId, user.getUsername(), "文件", "upload",
                String.format("分片上传文件「%s」（%d 个分片%s）", fileName, totalChunks,
                        instant ? "，去重命中" : ""), userFile.getId());

        // ---------- 7. 清理 ----------
        cleanupChunks(chunkDir);
        redis.delete(sessionKey);
        redis.delete(sessionKey + RECEIVED_SUFFIX);
        redis.opsForSet().remove(ACTIVE_PREFIX + userId, id);

        ChunkUploadCompleteVO vo = new ChunkUploadCompleteVO();
        vo.setFileId(userFile.getId());
        vo.setInstant(instant);
        vo.setFileSize(mergedSize);
        vo.setFileMd5(md5);
        log.info("分片上传完成: userId={}, fileId={}, size={}, instant={}",
                userId, userFile.getId(), mergedSize, instant);
        return vo;
    }

    // ==================== cancel ====================

    @Override
    public void cancel(String uploadId, Long userId) {
        String id = requireUploadId(uploadId);
        String sessionKey = SESSION_PREFIX + id;
        Map<Object, Object> session = redis.opsForHash().entries(sessionKey);
        if (session != null && !session.isEmpty()) {
            checkSessionOwner(session, userId);
        }
        Path chunkDir = chunkDirOf(id);
        if (Files.exists(chunkDir)) {
            Path owner = chunkDir.resolve(OWNER_FILE);
            if (Files.exists(owner)) {
                String content = readTextQuietly(owner);
                if (content != null && !String.valueOf(userId).equals(content.trim())) {
                    throw new BusinessException("无权操作该上传会话");
                }
            }
        }
        cleanupChunks(chunkDir);
        redis.delete(sessionKey);
        redis.delete(sessionKey + RECEIVED_SUFFIX);
        redis.opsForSet().remove(ACTIVE_PREFIX + userId, id);
        log.info("分片上传已取消: userId={}, uploadId={}", userId, id);
    }

    // ==================== 内部工具 ====================

    private Long createUserFileRecord(User user, String fileName, Long folderId, StorageFile storage, Long userId) {
        StorageFile current = storageFileMapper.selectById(storage.getId());
        if (current == null) {
            throw new BusinessException("物理文件记录不存在");
        }
        if (user.getUsedSpace() + current.getFileSize() > vipConfigMapper
                .selectById(user.getRole()).getMaxSpace()) {
            throw new BusinessException("存储空间不足");
        }
        current.setRefCount((current.getRefCount() == null ? 0 : current.getRefCount()) + 1);
        storageFileMapper.updateById(current);

        com.star.model.entity.File userFile = new com.star.model.entity.File();
        userFile.setFileName(fileName);
        userFile.setStorageId(current.getId());
        userFile.setUserId(userId);
        userFile.setFolderId(folderId);
        userFile.setDownloadCount(0);
        userFile.setStatus(1);
        fileMapper.insert(userFile);

        user.setUsedSpace(user.getUsedSpace() + current.getFileSize());
        userMapper.updateById(user);

        operationLogService.record(userId, user.getUsername(), "文件", "upload",
                String.format("上传文件「%s」（秒传）", fileName), userFile.getId());
        return userFile.getId();
    }

    /**
     * 第 index 个分片应有的字节数：最后一片可能不足 chunkSize。
     */
    private long chunkLengthOf(int index, int totalChunks, long fileSize, long chunkSize) {
        if (index < totalChunks - 1) {
            return chunkSize;
        }
        long last = fileSize - chunkSize * (totalChunks - 1);
        if (last > 0) {
            return last;
        }
        // 理论上不会走到这里；兜底防越界
        long mod = fileSize % chunkSize;
        return mod == 0 ? chunkSize : mod;
    }

    /** 扫描分片目录，返回已「完整」落盘的分片索引（升序） */
    private List<Integer> scanReceivedChunks(Path chunkDir, int totalChunks, long fileSize, long chunkSize) {
        List<Integer> list = new ArrayList<>();
        if (!Files.isDirectory(chunkDir)) {
            return list;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunkDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    // 写入中途断连留下的临时文件，直接清掉
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignore) {
                        // 忽略
                    }
                    continue;
                }
                if (!name.endsWith(".part")) {
                    continue;
                }
                String number = name.substring(0, name.length() - 5);
                if (!number.matches("\\d+")) {
                    continue;
                }
                int idx;
                try {
                    idx = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (idx < 0 || idx >= totalChunks) {
                    continue;
                }
                // 客户端中途断开时 transferTo 可能留下半截文件，这里按期望大小校验，
                // 不符的一律视为「未收到」并清掉，交由客户端重传。
                try {
                    if (Files.size(path) != chunkLengthOf(idx, totalChunks, fileSize, chunkSize)) {
                        Files.deleteIfExists(path);
                        log.warn("丢弃不完整分片: {} (index={})", path.getFileName(), idx);
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                list.add(idx);
            }
        } catch (IOException e) {
            log.warn("扫描分片目录失败: {}", e.getMessage());
        }
        Collections.sort(list);
        return list;
    }

    private Path chunkDirOf(String uploadId) {
        return Paths.get(uploadDir, ".chunks", uploadId).toAbsolutePath();
    }

    /** 目录归属：首次创建写入 .owner，之后只允许同一用户操作 */
    private void verifyOrClaimOwner(Path chunkDir, Long userId) {
        try {
            Files.createDirectories(chunkDir);
            Path owner = chunkDir.resolve(OWNER_FILE);
            if (Files.exists(owner)) {
                String content = readTextQuietly(owner);
                if (content != null && !content.trim().isEmpty() && !String.valueOf(userId).equals(content.trim())) {
                    throw new BusinessException("无权访问该上传会话");
                }
            } else {
                Files.write(owner, String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new BusinessException("上传目录初始化失败: " + e.getMessage());
        }
    }

    private void checkSessionOwner(Map<Object, Object> session, Long userId) {
        Object owner = session.get("userId");
        if (owner == null || !String.valueOf(userId).equals(String.valueOf(owner))) {
            throw new BusinessException("上传会话不属于当前用户");
        }
    }

    private String requireUploadId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (!UPLOAD_ID_PATTERN.matcher(id).matches()) {
            throw new BusinessException("上传会话 ID 非法");
        }
        return id;
    }

    private String normalizeMd5(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (value.length() != 32) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return value;
    }

    private String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 && dot < fileName.length() - 1) ? fileName.substring(dot + 1) : "";
    }

    private void cleanupChunks(Path chunkDir) {
        if (!Files.exists(chunkDir)) {
            return;
        }
        try {
            Files.walkFileTree(chunkDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("清理分片目录失败: {}", e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败: {}", path);
        }
    }

    /** 字节数组 → 小写十六进制 */
    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 临时文件改名成正式分片；优先原子移动，不支持时退回普通覆盖移动 */
    private void moveQuietly(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readTextQuietly(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
