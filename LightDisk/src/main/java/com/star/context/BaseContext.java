package com.star.context;

public class BaseContext {
    //创建ThreadLocal对象，用来存储Long类型的用户ID
    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();
    //将用户ID存入当前线程的储物柜
    public static void setCurrentId(Long id){
        threadLocal.set(id);
    }
    //从当前线程取出用户ID
    public static Long getCurrentId(){
        return threadLocal.get();
    }
    /**
     * 移除当前线程存的数据（防止内存泄漏，非常重要！）
     */
    public static void removeCurrentId() {
        threadLocal.remove();
    }
}
