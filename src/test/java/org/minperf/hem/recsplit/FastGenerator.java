package org.minperf.hem.recsplit;

import java.util.Arrays;

import org.minperf.BitBuffer;
import org.minperf.monotoneList.MultiStageMonotoneList;

public class FastGenerator {

    private final int leafSize;
    private final int averageBucketSize;
    private BitBuffer buff;
    private BitBuffer bucketBuff;

    FastGenerator(int leafSize, int averageBucketSize) {
        this.leafSize = leafSize;
        this.averageBucketSize = averageBucketSize;
    }

    BitBuffer generate(long[] keys) {
        generate(keys, 0, keys.length, 0);
        return buff;
    }

    void generate(long[] keys, int start, int end, int shift) {
        buff = new BitBuffer(keys.length * 10);
        int len = end - start;
        if (len <= 1) {
            return;
        }
        int bucketCount = Builder.getBucketCount(len, averageBucketSize);
        bucketBuff = new BitBuffer(len * 10 / bucketCount);
        int bucketBitCount = 31 - Integer.numberOfLeadingZeros(bucketCount);
        int bucketShift = 64 - bucketBitCount - shift;
        int[] startList = new int[bucketCount];
        int[] offsetList = new int[bucketCount + 1];
        sortIntoBuckets(keys, bucketShift, bucketCount, offsetList, startList);
        Arrays.fill(startList, 0);
        // move to the right (this could be avoided)
        System.arraycopy(offsetList, 0, offsetList, 1, bucketCount);
        offsetList[0] = 0;
        int startBucket = 0;
        for (int i = 0; i < bucketCount; i++) {
            int endBucket = offsetList[i + 1];
            generateBucket(keys, startBucket, endBucket, startList, i);
            startBucket = endBucket;
        }
        BitBuffer buff2 = new BitBuffer(keys.length * 10);
        buff2.writeEliasDelta(len + 1);
        MultiStageMonotoneList.generate(startList, buff2);
        MultiStageMonotoneList.generate(offsetList, buff2);
        buff2.write(buff);
        buff = buff2;
    }

    private static void sortIntoBuckets(long[] keys, int shift, int bucketCount, int[] offsetList, int[] array2) {
        if (bucketCount == 1) {
            offsetList[0] = keys.length;
            // in this case, shift is 64, which is problematic
            return;
        }
        boolean monotone = true;
        int last = 0;
        int[] pos = offsetList;
        for(long x : keys) {
            int b = (int) (x >>> shift);
            pos[b]++;
            if (b < last) {
                monotone = false;
            }
            last = b;
        }
        int[] stop = array2;
        int sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            int count = pos[i];
            pos[i] = sum;
            sum += count;
            stop[i] = sum;
        }
        if (monotone) {
            // shortcut if the list is already sorted
            return;
        }
        int i = 0;
        long x = keys[i];
        for(int bucket = 0;;) {
            int targetBucket = (int) (x >>> shift);
            int index = pos[targetBucket]++;
            long next = keys[index];
            keys[index] = x;
            x = next;
            if (index == i) {
                while (true) {
                    index = pos[bucket];
                    if (index < stop[bucket]) {
                        break;
                    }
                    bucket++;
                    if (bucket >= bucketCount) {
                        return;
                    }
                }
                i = index;
                x = keys[index];
            }
        }
    }

    private void generateBucket(long[] keys, int start, int end, int[] startList, int bucketId) {
        if (end == start) {
            startList[bucketId] = buff.position();
            return;
        }
        bucketBuff.clear();
        bucketBuff.seek(0);
        generateSet(keys, start, end, 0);
        if (buff.position() > 0) {
            int lastStart = startList[bucketId - 1];
            for(int overlap = 8; overlap > 0; overlap--) {
                if (buff.position() > overlap && bucketBuff.position() > overlap && buff.position() - lastStart >= overlap) {
                    long a = buff.readNumber(buff.position() - overlap, overlap);
                    long b = bucketBuff.readNumber(0, overlap);
                    if (a == b) {
                        buff.seek(buff.position() - overlap);
                        break;
                    }
                }
            }
        }
        startList[bucketId] = buff.position();
        while (bucketId > 0) {
            bucketId--;
            if (startList[bucketId] > buff.position()) {
                startList[bucketId] = buff.position();
            } else {
                break;
            }
        }
        buff.write(bucketBuff);
    }

    private void generateSet(long[] keys, int start, int end, int index) {
        int len = end - start;
        if (len <= leafSize) {
            switch (len) {
            case 0:
            case 1:
                return;
            case 2:
                generateBucket2(keys, start, index);
                return;
            case 3:
                generateBucket3(keys, start, index);
                return;
            case 4:
                generateBucket4(keys, start, index);
                return;
            case 5:
                generateBucket5(keys, start, index);
                return;
            case 6:
                generateBucket6(keys, start, index);
                return;
            }
        }
        if (len > 64) {
            // TODO not supported currently
            throw new IllegalArgumentException("len " + len);
        }
        generateSetHalf(keys, start, end, index);
    }

    private void generateSetHalf(long[] keys, int start, int end, int index) {
        int size = end - start;
        int first = size / 2;
        long bits;
        int count;
        int oldIndex = index;
        for (;; index++) {
            bits = 0;
            count = 0;
            for (int i = start; i < end; i++) {
                long x = Builder.supplementalHash(keys[i], index) & 1;
                bits |= x << (i - start);
                count += x;
            }
            if (count == size - first) {
                break;
            }
        }
        sort(keys, start, end, bits);
        emit(size, index - oldIndex);
        generateSet(keys, start, start + size - count, index + 1);
        generateSet(keys, start + size - count, start + size, index + 1);
    }

    private static void sort(long[] keys, int start, int end, long bits) {
        int len = end - start;
        for (int l = 0, r = len - 1; l < r;) {
            while (((bits >>> l) & 1) == 0) {
                l++;
                if (l >= r) {
                    return;
                }
            }
            while (((bits >>> r) & 1) == 1) {
                r--;
                if (l >= r) {
                    return;
                }
            }
            long temp = keys[start + l];
            keys[start + l] = keys[start + r];
            keys[start + r] = temp;
            l++;
            r--;
        }
    }

    private void emit(int size, int indexDiff) {
        int shift = Builder.getGolombRiceShift(size);
        bucketBuff.writeGolombRice(shift, indexDiff);
    }

    private void generateBucket2(long[] keys, int start, int index) {
        int oldIndex = index;
        for (;; index++) {
            int a = Builder.supplementalHash(keys[start], index) & 1;
            int b = Builder.supplementalHash(keys[start + 1], index) & 1;
            if (a != b) {
                break;
            }
        }
        emit(2, index - oldIndex);
    }

    private void generateBucket3(long[] keys, int start, int index) {
        int oldIndex = index;
        for (;; index++) {
            int a = Builder.reduce(Builder.supplementalHash(keys[start], index), 3);
            int b = Builder.reduce(Builder.supplementalHash(keys[start + 1], index), 3);
            int c = Builder.reduce(Builder.supplementalHash(keys[start + 2], index), 3);
            if (a != b && a != c && b != c) {
                break;
            }
        }
        emit(3, index - oldIndex);
    }

    private void generateBucket4(long[] keys, int start, int index) {
        int oldIndex = index;
        for (;; index++) {
            int a = Builder.supplementalHash(keys[start], index) & 3;
            int b = Builder.supplementalHash(keys[start + 1], index) & 3;
            int c = Builder.supplementalHash(keys[start + 2], index) & 3;
            int d = Builder.supplementalHash(keys[start + 3], index) & 3;
            if (((1 << a) | (1 << b) | (1 << c) | (1 << d)) == 0xf) {
                break;
            }
        }
        emit(4, index - oldIndex);
    }

    private void generateBucket5(long[] keys, int start, int index) {
        int oldIndex = index;
        for (;; index++) {
            int a = Builder.reduce(Builder.supplementalHash(keys[start], index), 5);
            int b = Builder.reduce(Builder.supplementalHash(keys[start + 1], index), 5);
            int c = Builder.reduce(Builder.supplementalHash(keys[start + 2], index), 5);
            int d = Builder.reduce(Builder.supplementalHash(keys[start + 3], index), 5);
            int e = Builder.reduce(Builder.supplementalHash(keys[start + 4], index), 5);
            if (((1 << a) | (1 << b) | (1 << c) | (1 << d) | (1 << e)) == 0x1f) {
                break;
            }
        }
        emit(5, index - oldIndex);
    }

    private void generateBucket6(long[] keys, int start, int index) {
        int oldIndex = index;
        for (;; index++) {
            int a = Builder.reduce(Builder.supplementalHash(keys[start], index), 6);
            int b = Builder.reduce(Builder.supplementalHash(keys[start + 1], index), 6);
            int c = Builder.reduce(Builder.supplementalHash(keys[start + 2], index), 6);
            int d = Builder.reduce(Builder.supplementalHash(keys[start + 3], index), 6);
            int e = Builder.reduce(Builder.supplementalHash(keys[start + 4], index), 6);
            int f = Builder.reduce(Builder.supplementalHash(keys[start + 5], index), 6);
            if (((1 << a) | (1 << b) | (1 << c) | (1 << d) | (1 << e) | (1 << f)) == 0x3f) {
                break;
            }
        }
        emit(6, index - oldIndex);
    }

}
