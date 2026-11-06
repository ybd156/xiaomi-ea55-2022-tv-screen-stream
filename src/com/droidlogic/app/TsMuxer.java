package com.droidlogic.app;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 极简 MPEG-TS 封装器：只做单路 H.264 视频（stream_type 0x1B）。
 * Android 的 MediaMuxer 不支持 TS 输出，所以自己封。
 *
 * 输出：PAT(PID 0) + PMT(PID 0x1000) + 视频 PES(PID 0x100)，188 字节包。
 */
public class TsMuxer {

    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x100;
    private static final int TS_SIZE = 188;

    private final OutputStream out;
    private final byte[] pkt = new byte[TS_SIZE];
    private long lastPsiMs = 0;
    private int ccPat = 0, ccPmt = 0, ccVideo = 0;

    public TsMuxer(OutputStream out) {
        this.out = out;
    }

    private static int[] crcTable;

    private static int crc32(byte[] data, int off, int len) {
        if (crcTable == null) {
            crcTable = new int[256];
            for (int i = 0; i < 256; i++) {
                int c = i << 24;
                for (int k = 0; k < 8; k++) {
                    c = ((c & 0x80000000) != 0) ? (c << 1) ^ 0x04C11DB7 : (c << 1);
                }
                crcTable[i] = c;
            }
        }
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ crcTable[((crc >>> 24) ^ (data[i] & 0xff)) & 0xff];
        }
        return crc;
    }

    /** 写 PAT + PMT（建议每 500ms 一次） */
    public synchronized void writePsi() throws IOException {
        // ---- PAT ----
        byte[] pat = new byte[16];
        pat[0] = 0x00;                       // table_id
        pat[1] = (byte) 0xB0;                // section_syntax_indicator=1, len high=0
        pat[2] = 0x0D;                       // section_length = 13
        pat[3] = 0x00; pat[4] = 0x01;        // transport_stream_id
        pat[5] = (byte) 0xC1;                // version 0, current_next 1
        pat[6] = 0x00; pat[7] = 0x00;        // section_number, last_section_number
        pat[8] = 0x00; pat[9] = 0x01;        // program_number = 1
        pat[10] = (byte) (0xE0 | (PID_PMT >> 8));
        pat[11] = (byte) (PID_PMT & 0xff);
        int c = crc32(pat, 0, 12);
        pat[12] = (byte) (c >>> 24); pat[13] = (byte) (c >>> 16);
        pat[14] = (byte) (c >>> 8);  pat[15] = (byte) c;
        writeSection(0x00, pat, ccPat = (ccPat + 1) & 0x0f);

        // ---- PMT ----
        byte[] pmt = new byte[21];
        pmt[0] = 0x02;
        pmt[1] = (byte) 0xB0;
        pmt[2] = 0x12;                       // section_length = 18
        pmt[3] = 0x00; pmt[4] = 0x01;        // program_number
        pmt[5] = (byte) 0xC1;
        pmt[6] = 0x00; pmt[7] = 0x00;
        pmt[8] = (byte) (0xE0 | (PID_VIDEO >> 8));   // PCR_PID
        pmt[9] = (byte) (PID_VIDEO & 0xff);
        pmt[10] = (byte) 0xF0; pmt[11] = 0x00;       // program_info_length = 0
        pmt[12] = 0x1B;                              // stream_type = H.264
        pmt[13] = (byte) (0xE0 | (PID_VIDEO >> 8));
        pmt[14] = (byte) (PID_VIDEO & 0xff);
        pmt[15] = (byte) 0xF0; pmt[16] = 0x00;       // ES_info_length = 0
        c = crc32(pmt, 0, 17);
        pmt[17] = (byte) (c >>> 24); pmt[18] = (byte) (c >>> 16);
        pmt[19] = (byte) (c >>> 8);  pmt[20] = (byte) c;
        writeSection(PID_PMT, pmt, ccPmt = (ccPmt + 1) & 0x0f);
    }

    private void writeSection(int pid, byte[] section, int cc) throws IOException {
        // PSI 段：payload_unit_start=1，且 payload 首字节必须是 pointer_field(0x00)
        int payloadLen = section.length;
        int offset = 0;
        boolean first = true;
        while (offset < payloadLen) {
            java.util.Arrays.fill(pkt, (byte) 0xFF);
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) (pid & 0xff);
            pkt[3] = (byte) (0x10 | (cc & 0x0f));    // payload only
            int hdr = 4;
            if (first) {
                pkt[4] = 0x00;      // pointer_field
                hdr = 5;
            }
            int n = Math.min(TS_SIZE - hdr, payloadLen - offset);
            System.arraycopy(section, offset, pkt, hdr, n);
            out.write(pkt);
            offset += n;
            first = false;
        }
    }

    /** 写一个 H.264 访问单元（Annex-B 字节流），ptsUs 为微秒 */
    public synchronized void writeVideo(byte[] data, long ptsUs, boolean keyframe) throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastPsiMs > 500) {
            writePsi();
            lastPsiMs = now;
        }

        // ---- PES 头 ----
        long pts = (ptsUs * 9) / 100;     // 90kHz
        final int PES_HDR_DATA_LEN = 5;   // 只有 PTS 占 5 字节
        int pesHeaderLen = 3 + PES_HDR_DATA_LEN;   // 2 字节 flags + 1 字节长度 + 5 字节 PTS
        int pesLen = data.length + pesHeaderLen;
        byte[] pes = new byte[9 + pesHeaderLen + data.length];
        pes[0] = 0x00; pes[1] = 0x00; pes[2] = 0x01; pes[3] = (byte) 0xE0;
        pes[4] = (byte) ((pesLen > 0xFFFF ? 0 : (pesLen >> 8)) & 0xff);
        pes[5] = (byte) (pesLen > 0xFFFF ? 0 : (pesLen & 0xff));
        pes[6] = (byte) 0x80;             // '10' marker
        pes[7] = (byte) 0x80;             // PTS only
        pes[8] = (byte) PES_HDR_DATA_LEN;
        pes[9]  = (byte) (0x21 | ((pts >> 29) & 0x0E));
        pes[10] = (byte) ((pts >> 22) & 0xFF);
        pes[11] = (byte) (((pts >> 14) & 0xFE) | 0x01);
        pes[12] = (byte) ((pts >> 7) & 0xFF);
        pes[13] = (byte) (((pts << 1) & 0xFE) | 0x01);
        System.arraycopy(data, 0, pes, 14, data.length);

        // ---- 打成 TS 包 ----
        int offset = 0;
        boolean first = true;
        while (offset < pes.length) {
            java.util.Arrays.fill(pkt, (byte) 0xFF);
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((PID_VIDEO >> 8) & 0x1F));
            pkt[2] = (byte) (PID_VIDEO & 0xff);
            int headerLen = 4;
            boolean needPcr = first && keyframe;
            if (needPcr) {
                // adaptation field 带 PCR，占 8 字节
                pkt[3] = (byte) (0x30 | (ccVideo & 0x0f));
                pkt[4] = 7;                       // adaptation_field_length
                pkt[5] = 0x10;                    // PCR flag
                long pcrBase = pts;               // 简化：PCR = PTS
                pkt[6] = (byte) (pcrBase >> 25);
                pkt[7] = (byte) (pcrBase >> 17);
                pkt[8] = (byte) (pcrBase >> 9);
                pkt[9] = (byte) (pcrBase >> 1);
                pkt[10] = (byte) (((pcrBase & 0x1) << 7) | 0x7E);
                pkt[11] = 0x00;
                headerLen = 12;
            } else {
                pkt[3] = (byte) (0x10 | (ccVideo & 0x0f));
            }
            int n = Math.min(TS_SIZE - headerLen, pes.length - offset);
            System.arraycopy(pes, offset, pkt, headerLen, n);
            out.write(pkt);
            offset += n;
            first = false;
            ccVideo = (ccVideo + 1) & 0x0f;
        }
    }
}
