package butvan.agent.network.review.service;

import java.util.concurrent.Semaphore;

/** 本机有界复盘执行名额；统计与生活复盘按用户共享，碰撞只降低并发。 */
public final class ReviewGenerationGate {
    private static final Semaphore[] SLOTS = new Semaphore[128];
    static { for (int i = 0; i < SLOTS.length; i++) SLOTS[i] = new Semaphore(1); }
    private ReviewGenerationGate() {}
    public static Lease tryEnter(String owner) {
        Semaphore slot = SLOTS[Math.floorMod(owner.hashCode(), SLOTS.length)];
        return slot.tryAcquire() ? new Lease(slot) : null;
    }
    /** 租约随真实生产者退出，不能在仅停止等待时释放。 */
    public static final class Lease implements AutoCloseable {
        private Semaphore slot;
        private Lease(Semaphore slot) { this.slot = slot; }
        @Override public void close() { if (slot != null) { slot.release(); slot = null; } }
    }
}
