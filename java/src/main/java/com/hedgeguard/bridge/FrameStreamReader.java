package com.hedgeguard.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * HGCP 流式切帧器（粘包 / 半包 / 逐字节喂）。
 *
 * <p>与 C++ 侧 {@code session} 层同一语义：缓冲不足一帧（含不足 32B 头）⇒ 保留缓冲、返回空列表，
 * <b>不消费任何字节</b>；凑够一帧则解出并推进偏移。
 *
 * <p>调用方（{@link HgcpClient} 读线程）单线程使用，本类<b>非线程安全</b>。
 */
public final class FrameStreamReader {

    private byte[] buf = new byte[8192];
    private int size;

    /**
     * 追加字节。
     *
     * @param data   数据
     * @param length 有效长度
     */
    public void feed(byte[] data, int length) {
        ensureCapacity(size + length);
        System.arraycopy(data, 0, buf, size, length);
        size += length;
    }

    /**
     * 追加字节（全量）。
     *
     * @param data 数据
     */
    public void feed(byte[] data) {
        feed(data, data.length);
    }

    /**
     * 取出缓冲中所有完整帧。缓冲不足时返回空列表且不消费。
     *
     * @return 解码出的帧列表（按到达顺序）
     * @throws HgcpProtocolException 帧校验失败（坏 MAGIC / 版本 / RESERVED / 超限 / CRC 错）
     */
    public List<HgcpFrame.Decoded> drain() {
        List<HgcpFrame.Decoded> out = new ArrayList<>();
        int off = 0;
        while (true) {
            HgcpFrame.Decoded d = HgcpFrame.decode(buf, off, size);
            if (d.status == HgcpFrame.Status.NEED_MORE) {
                break;
            }
            if (d.status != HgcpFrame.Status.OK) {
                // 成帧失败：帧边界不再可信（与 C++ 侧同姿态——不尝试重同步）
                throw new HgcpProtocolException(d.status);
            }
            out.add(d);
            off += d.consumed;
        }
        if (off > 0) {
            int remain = size - off;
            System.arraycopy(buf, off, buf, 0, remain);
            size = remain;
        }
        return out;
    }

    /** 当前缓冲字节数（诊断用）。 */
    public int pending() {
        return size;
    }

    /** 清空缓冲（断连重建时调用）。 */
    public void reset() {
        size = 0;
    }

    private void ensureCapacity(int need) {
        if (need <= buf.length) {
            return;
        }
        int cap = buf.length;
        while (cap < need) {
            cap *= 2;
        }
        byte[] next = new byte[cap];
        System.arraycopy(buf, 0, next, 0, size);
        buf = next;
    }
}
