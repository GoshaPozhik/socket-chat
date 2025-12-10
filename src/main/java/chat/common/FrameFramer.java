package chat.common;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class FrameFramer {
    private final int maxFrameBytes;

    public FrameFramer(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public List<byte[]> feed(ByteBuffer buf) {
        List<byte[]> frames = new ArrayList<>();

        while (true) {
            if (buf.remaining() < 4) break;

            buf.mark();
            int len = buf.getInt();

            if (len <= 0 || len > maxFrameBytes) {
                frames.add(null);
                return frames;
            }

            if (buf.remaining() < len) {
                buf.reset();
                break;
            }

            byte[] body = new byte[len];
            buf.get(body);
            frames.add(body);
        }

        return frames;
    }
}
