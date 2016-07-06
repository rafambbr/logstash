package org.logstash.common.io;

import org.logstash.ackedqueue.Checkpoint;
import org.logstash.ackedqueue.Queueable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MemoryElementIOStream {
    static final int CHECKSUM_SIZE = Integer.BYTES;
    static final int LENGTH_SIZE = Integer.BYTES;
    static final int SEQNUM_SIZE = Long.BYTES;
    static final int MIN_RECORD_SIZE = SEQNUM_SIZE + LENGTH_SIZE + CHECKSUM_SIZE;
    static final int HEADER_SIZE = 1;     // version byte
    private final byte[] buffer;
    private final int byteSize;
    private int writePosition;
    private int elementCount;
    private long startSeqNum;
    private ByteBufferStreamInput streamedInput;
    private ByteArrayStreamOutput streamedOutput;
    private BufferedChecksumStreamOutput crcWrappedOutput;

    public MemoryElementIOStream(int byteSize) {
        this(new byte[byteSize], 1L, 0); // empty array, first seqNum, no elements written yet
    }
    public MemoryElementIOStream(byte[] initialBytes, Checkpoint ckp) {
        this(initialBytes, ckp.getMinSeqNum(), ckp.getElementCount());
    }
    public MemoryElementIOStream(byte[] initialBytes, long minSeqNum, int elementCount) {
        buffer = initialBytes;
        byteSize = initialBytes.length;
        startSeqNum = minSeqNum;
        this.elementCount = elementCount;
        writePosition = HEADER_SIZE; //skip header bytes
        streamedInput = new ByteBufferStreamInput(ByteBuffer.wrap(buffer));
        streamedOutput = new ByteArrayStreamOutput(buffer);
        crcWrappedOutput = new BufferedChecksumStreamOutput(streamedOutput);
        if (this.elementCount == 0) {
            addHeader();
            try {
                streamedInput.skip(HEADER_SIZE);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            long readSeqNum;
            try {
                BufferedChecksumStreamInput in = new BufferedChecksumStreamInput(streamedInput);
                in.skip(HEADER_SIZE);
                readSeqNum = in.readLong();
                //verify that the buffer starts with the min sequence number
                if (readSeqNum != this.startSeqNum) {
                    // throw tragic error
                }
                verifyChecksum(in);
                for (int i = 1; i < this.elementCount; i++) {
                    in.skip(SEQNUM_SIZE);
                    verifyChecksum(in);
                }
                streamedInput.reset();
                streamedInput.skip(HEADER_SIZE);
            } catch (IOException e) {
                e.printStackTrace();
                // throw tragic error
            }
        }
    }

    public int getWritePosition() {
        return writePosition;
    }

    public int getElementCount() {
        return elementCount;
    }

    public long getStartSeqNum() {
        return startSeqNum;
    }

    private void addHeader() {
        buffer[0] = Checkpoint.VERSION;
    }

    private void verifyChecksum(BufferedChecksumStreamInput in) throws IOException {
        in.resetDigest();
        in.readByteArray();
        int actualChecksum = (int) in.getChecksum();
        int expectedChecksum = in.readInt();
        if (actualChecksum != expectedChecksum) {
            // explode with tragic error
        }
    }

    public static int recordSize(byte[] data) {
        return MIN_RECORD_SIZE + data.length;
    }

    public static int recordSize(int length) {
        return MIN_RECORD_SIZE + length;
    }

    public boolean hasSpace(int byteSize) {
        return this.byteSize >= writePosition + recordSize(byteSize);
    }

    public long write(Queueable element) throws IOException {
        byte[] bytes = element.serialize();
        long seqNum = element.getSeqNum();
        return write(bytes, seqNum);
    }

    public long write(byte[] bytes, Queueable element) throws IOException {
        long seqNum = element.getSeqNum();
        return write(bytes, seqNum);
    }

    public long write(byte[] bytes, long seqNum) throws IOException {
        int writeLength = recordSize(bytes);
        writeRecordToBuffer(seqNum, bytes, writeLength);
        writePosition += writeLength;
        if (elementCount == 0) {
            this.startSeqNum = seqNum;
        }
        elementCount++;
        return seqNum;
    }

    private void writeRecordToBuffer(long seqNum, byte[] data, int len) throws IOException {
        streamedOutput.setWriteWindow(writePosition, len);
        crcWrappedOutput.writeLong(seqNum);
        crcWrappedOutput.resetDigest();
        crcWrappedOutput.writeByteArray(data);
        long checksum = crcWrappedOutput.getChecksum();
        crcWrappedOutput.writeInt((int) checksum);
        crcWrappedOutput.flush();
        crcWrappedOutput.close();
    }

    public List<ReadElementValue> read(long seqNum, int limit) throws IOException {
        return read(limit);
    }

    public List<ReadElementValue> read(int limit) throws IOException {
        ArrayList<ReadElementValue> result = new ArrayList<>();
        int upto = available(limit);
        for (int i = 0; i < upto; i++) {
            long seqnum = readSeqNum();
            byte[] data = readData();
            skipChecksum();
            result.add(new ReadElementValue(seqnum, data));
        }
        return result;
    }

    private long readSeqNum() throws IOException {
        return streamedInput.readLong();
    }

    private byte[] readData() throws IOException {
        return streamedInput.readByteArray();
    }

    private void skipChecksum() throws IOException {
        streamedInput.skip(CHECKSUM_SIZE);
    }

    private int available(int sought) {
        if (elementCount < 1) return 0;
        if (elementCount < sought) return elementCount;
        return sought;
    }
}