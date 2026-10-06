/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：复用固定大小的 ByteBuffer，降低编解码路径上的分配与 GC 压力。
 *
 * NOTE: 当前没有任何编解码器接入本类，池内的缓冲区不会被复用，属于预留实现。
 */
package com.multiplayer.ender.logic;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 定长 ByteBuffer 对象池。
 *
 * 本类只负责缓冲区的借出与归还，不感知缓冲区的内容与生命周期语义。
 *
 * 设计约束：
 * 1. 池内缓冲区一律由本类分配，调用方不得把自建的 ByteBuffer 交给 release，
 *    否则池中会混入大小不一致的缓冲区。
 * 2. release 只做 clear 不做数据清零，归还后的内容仍可被下一次 acquire 的调用方读到。
 * 3. 池只在进程内共享，不提供跨进程或跨类加载器的隔离。
 *
 * 线程安全性：pool 为 ConcurrentLinkedQueue，acquire 与 release 可被多线程并发调用。
 * 池大小判断与入队不是原子操作，极端并发下池内元素数可能短暂超过 MAX_POOL_SIZE。
 *
 * TODO(P3, 2026-10-06): 明确本类是保留接入还是删除，见 docs/06-logic-and-code-quality.md。
 *
 * @since 1.0
 */
public class BufferPool {

    /** 单个缓冲区容量，单位字节，固定为 4KB，与本类所有缓冲区保持一致。 */
    private static final int BUFFER_SIZE = 4096;

    /** 池内允许缓存的缓冲区数量上限，超出后 release 直接丢弃缓冲区，取值范围大于 0。 */
    private static final int MAX_POOL_SIZE = 100;

    /**
     * 空闲缓冲区队列。
     *
     * 永不为 null，进程存活期间一直存在；元素个数理论上不超过 MAX_POOL_SIZE，但存在
     * 「判断大小」与「入队」之间的竞态窗口，短暂超额是预期行为。
     */
    private static final Queue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

    /**
     * 借出一个缓冲区。
     *
     * 池非空时复用队首元素并复位其 position/limit，池空时新分配一个。
     * 返回值永不为 null，但状态不可预知：可能是全新的零填充缓冲区，也可能是上次归还的
     * 脏数据缓冲区，调用方写入前必须自行确认有效数据长度。
     *
     * @return 容量固定为 BUFFER_SIZE 的 ByteBuffer，永不为 null，position 为 0
     */
    public static ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            return ByteBuffer.allocate(BUFFER_SIZE);
        }
        buffer.clear();
        return buffer;
    }

    /**
     * 归还一个缓冲区。
     *
     * 归还仅是「建议」：池已满时缓冲区被静默丢弃，不报错也不阻塞。
     * 同一个缓冲区被重复归还会在池中出现多份引用，调用方需保证不放回已归还的对象。
     *
     * @param buffer 要归还的缓冲区，允许为 null，为 null 时本方法不做任何事
     */
    public static void release(ByteBuffer buffer) {
        if (buffer != null && pool.size() < MAX_POOL_SIZE) {
            buffer.clear();
            pool.offer(buffer);
        }
    }
}
