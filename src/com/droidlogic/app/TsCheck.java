package com.droidlogic.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * 用**软件**解码器验证我们生成的 TS 能不能正常解出画面。
 * 故意用 OMX.google.h264.decoder，避免占用电视的硬件解码器、干扰正在播放的节目。
 */
public class TsCheck {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/data/local/tmp/test.ts";
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            System.out.println("track " + i + ": " + mime + " " + f);
            if (mime.startsWith("video/")) { track = i; fmt = f; }
        }
        if (track < 0) { System.out.println("NO VIDEO TRACK"); return; }
        ex.selectTrack(track);

        MediaCodec dec = MediaCodec.createByCodecName("OMX.google.h264.decoder");
        dec.configure(fmt, null, null, 0);
        dec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int frames = 0;
        long bytes = 0;
        boolean eos = false;
        long firstLuma = -1;
        while (!eos) {
            int inIdx = dec.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                ByteBuffer in = dec.getInputBuffer(inIdx);
                int n = ex.readSampleData(in, 0);
                if (n < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eos = true;
                } else {
                    bytes += n;
                    dec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                    ex.advance();
                }
            }
            int outIdx;
            while ((outIdx = dec.dequeueOutputBuffer(info, 0)) >= 0) {
                if (info.size > 0) {
                    frames++;
                    if (firstLuma < 0) {
                        ByteBuffer ob = dec.getOutputBuffer(outIdx);
                        int sum = 0, cnt = 0;
                        for (int k = 0; k < Math.min(2000, ob.remaining()); k++) {
                            sum += ob.get(k) & 0xff;
                            cnt++;
                        }
                        firstLuma = cnt > 0 ? sum / cnt : -1;
                    }
                }
                dec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
            }
        }
        dec.stop();
        dec.release();
        ex.release();
        System.out.println("DECODED frames=" + frames + " bytes=" + bytes + " firstFrameYavg=" + firstLuma);
    }
}
