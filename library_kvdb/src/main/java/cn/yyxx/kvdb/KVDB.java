package cn.yyxx.kvdb;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */

@SuppressWarnings("rawtypes")
public final class KVDB {
    private static final String BOTH_FILES_ERROR = "both files error";
    private static final String PARSE_DATA_FAILED = "parse dara failed";
    private static final String OPEN_FILE_FAILED = "open file failed";
    private static final String MAP_FAILED = "map failed";

    static final String TRUNCATE_FINISH = "truncate finish";
    static final String GC_FINISH = "gc finish";

    private static final String A_SUFFIX = ".kva";
    private static final String B_SUFFIX = ".kvb";
    private static final String C_SUFFIX = ".kvc";
    private static final String TEMP_SUFFIX = ".tmp";

    private static final int DATA_SIZE_LIMIT = 1 << 29;

    private static final int[] TYPE_SIZE = {0, 1, 4, 4, 8, 8};
    private static final byte[] EMPTY_ARRAY = new byte[0];
    private static final int DATA_START = 12;
    private static final int BASE_GC_KEYS_THRESHOLD = 80;
    private static final int BASE_GC_BYTES_THRESHOLD = 4096;
    private final int INTERNAL_LIMIT = Config.internalLimit;

    private static final int PAGE_SIZE = Util.getPageSize();
    private static final int DOUBLE_LIMIT = Math.max(PAGE_SIZE << 1, 1 << 14);
    private static final int TRUNCATE_THRESHOLD = DOUBLE_LIMIT << 1;

    private final String path;
    private final String name;
    private final Map<String, IEncoder> encoderMap;
    private final ILogger logger = Config.sLogger;

    private FileChannel aChannel;
    private FileChannel bChannel;
    private MappedByteBuffer aBuffer;
    private MappedByteBuffer bBuffer;
    private Buffer buffer;

    private int dataEnd;
    private long checksum;
    private final Map<String, Container.BaseContainer> data = new HashMap<>();
    private boolean startLoading = false;

    private int updateStart;
    private int updateSize;
    private int removeStart;
    private boolean sizeChanged;

    private final List<String> deletedFiles = new ArrayList<>();

    private String tempExternalName;

    private int invalidBytes;
    private final ArrayList<Segment> invalids = new ArrayList<>();

    // The default writing mode is non-blocking (write partial data with mmap).
    // If mmap API throw IOException, degrade to blocking mode (write all data to disk with blocking I/O).
    // User could set blocking mode by FastKV.Builder
    static final int NON_BLOCKING = 0;
    static final int ASYNC_BLOCKING = 1;
    static final int SYNC_BLOCKING = 2;
    private int writingMode;

    // Only take effect when mode is not NON_BLOCKING
    private boolean autoCommit = true;

    private final Executor applyExecutor = new LimitExecutor();
    private final TagExecutor externalExecutor = new TagExecutor();
    private final WeakCache externalCache = new WeakCache();
    private final WeakCache bigValueCache = new WeakCache();

    KVDB(final String path, final String name, IEncoder[] encoders, int writingMode) {
        this.path = path;
        this.name = name;
        this.writingMode = writingMode;

        Map<String, IEncoder> map = new HashMap<>();
        StringSetEncoder encoder = StringSetEncoder.INSTANCE;
        map.put(encoder.tag(), encoder);
        if (encoders != null && encoders.length > 0) {
            for (IEncoder e : encoders) {
                String tag = e.tag();
                if (map.containsKey(tag)) {
                    error("duplicate encoder tag:" + tag);
                } else {
                    map.put(tag, e);
                }
            }
        }
        this.encoderMap = map;

        synchronized (data) {
            Config.getExecutor().execute(this::loadData);
            while (!startLoading) {
                try {
                    // wait util loadData() get the object lock
                    data.wait();
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    private synchronized void loadData() {
        // we got the object lock, notify the waiter to continue the constructor
        synchronized (data) {
            startLoading = true;
            data.notify();
        }
        long start = System.nanoTime();
        if (!loadFromCFile() && writingMode == NON_BLOCKING) {
            loadFromABFile();
        }
        if (buffer == null) {
            buffer = new Buffer(PAGE_SIZE);
        }
        if (logger != null) {
            long t = (System.nanoTime() - start) / 1000000;
            info("loading finish, data len:" + dataEnd + ", get keys:" + data.size() + ", use time:" + t + " ms");
        }
    }

    private void loadFromABFile() {
        File aFile = new File(path, name + A_SUFFIX);
        File bFile = new File(path, name + B_SUFFIX);
        try {
            if (!Util.makeFileIfNotExist(aFile) || !Util.makeFileIfNotExist(bFile)) {
                error(new Exception(OPEN_FILE_FAILED));
                toBlockingMode();
                return;
            }
            RandomAccessFile aAccessFile = new RandomAccessFile(aFile, "rw");
            RandomAccessFile bAccessFile = new RandomAccessFile(bFile, "rw");
            long aFileLen = aAccessFile.length();
            long bFileLen = bAccessFile.length();
            aChannel = aAccessFile.getChannel();
            bChannel = bAccessFile.getChannel();
            try {
                aBuffer = aChannel.map(FileChannel.MapMode.READ_WRITE, 0, aFileLen > 0 ? aFileLen : PAGE_SIZE);
                aBuffer.order(ByteOrder.LITTLE_ENDIAN);
                bBuffer = bChannel.map(FileChannel.MapMode.READ_WRITE, 0, bFileLen > 0 ? bFileLen : PAGE_SIZE);
                bBuffer.order(ByteOrder.LITTLE_ENDIAN);
            } catch (IOException e) {
                error(e);
                toBlockingMode();
                tryBlockingIO(aFile, bFile);
                return;
            }
            buffer = new Buffer(aBuffer.capacity());

            if (aFileLen == 0 && bFileLen == 0) {
                dataEnd = DATA_START;
            } else {
                int aDataSize = aBuffer.getInt();
                long aCheckSum = aBuffer.getLong();
                int bDataSize = bBuffer.getInt();
                long bCheckSum = bBuffer.getLong();

                boolean isAValid = false;
                if (aDataSize >= 0 && (aDataSize <= aFileLen - DATA_START)) {
                    dataEnd = DATA_START + aDataSize;
                    aBuffer.rewind();
                    aBuffer.get(buffer.hb, 0, dataEnd);
                    if (aCheckSum == buffer.getChecksum(DATA_START, aDataSize) && parseData() == 0) {
                        checksum = aCheckSum;
                        isAValid = true;
                    }
                }
                if (isAValid) {
                    if (aFileLen != bFileLen || !isABFileEqual()) {
                        warning(new Exception("B file error"));
                        copyBuffer(aBuffer, bBuffer, dataEnd);
                    }
                } else {
                    boolean isBValid = false;
                    if (bDataSize >= 0 && (bDataSize <= bFileLen - DATA_START)) {
                        data.clear();
                        clearInvalid();
                        dataEnd = DATA_START + bDataSize;
                        if (buffer.hb.length != bBuffer.capacity()) {
                            buffer = new Buffer(bBuffer.capacity());
                        }
                        bBuffer.rewind();
                        bBuffer.get(buffer.hb, 0, dataEnd);
                        if (bCheckSum == buffer.getChecksum(DATA_START, bDataSize) && parseData() == 0) {
                            warning(new Exception("A file error"));
                            copyBuffer(bBuffer, aBuffer, dataEnd);
                            checksum = bCheckSum;
                            isBValid = true;
                        }
                    }
                    if (!isBValid) {
                        error(BOTH_FILES_ERROR);
                        resetData();
                    }
                }
            }
        } catch (Exception e) {
            error(e);
            clearData();
            toBlockingMode();
        }
    }

    private void tryBlockingIO(File aFile, File bFile) {
        try {
            if (loadWithBlockingIO(aFile)) {
                return;
            }
        } catch (IOException e) {
            warning(e);
        }
        clearData();
        try {
            if (loadWithBlockingIO(bFile)) {
                return;
            }
        } catch (IOException e) {
            warning(e);
        }
        clearData();
    }

    private boolean isABFileEqual() {
        Buffer tempBuffer = new Buffer(dataEnd);
        bBuffer.rewind();
        bBuffer.get(tempBuffer.hb, 0, dataEnd);
        byte[] a = buffer.hb;
        byte[] b = tempBuffer.hb;
        for (int i = 0; i < dataEnd; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean loadFromCFile() {
        boolean hadWriteToABFile = false;
        File cFile = new File(path, name + C_SUFFIX);
        File tmpFile = new File(path, name + TEMP_SUFFIX);
        try {
            File srcFile = null;
            if (cFile.exists()) {
                srcFile = cFile;
            } else if (tmpFile.exists()) {
                srcFile = tmpFile;
            }
            if (srcFile != null) {
                if (loadWithBlockingIO(srcFile)) {
                    if (writingMode == NON_BLOCKING) {
                        if (writeToABFile(buffer)) {
                            info("recover from c file");
                            hadWriteToABFile = true;
                            deleteCFiles();
                        } else {
                            writingMode = ASYNC_BLOCKING;
                        }
                    }
                } else {
                    clearData();
                    deleteCFiles();
                }
            } else {
                // Handle the case:
                // User opening with non-blocking mode at first, and change to blocking mode in later.
                if (writingMode != NON_BLOCKING) {
                    File aFile = new File(path, name + A_SUFFIX);
                    File bFile = new File(path, name + B_SUFFIX);
                    if (aFile.exists() && bFile.exists()) {
                        tryBlockingIO(aFile, bFile);
                    }
                }
            }
        } catch (Exception e) {
            error(e);
        }
        return hadWriteToABFile;
    }

    private boolean loadWithBlockingIO(File srcFile) throws IOException {
        long fileLen = srcFile.length();
        if (fileLen == 0 || fileLen > DATA_SIZE_LIMIT) {
            return false;
        }
        int fileSize = (int) fileLen;
        int capacity = getNewCapacity(PAGE_SIZE, fileSize);
        Buffer buffer;
        if (this.buffer != null && this.buffer.hb.length == capacity) {
            buffer = this.buffer;
            buffer.position = 0;
        } else {
            buffer = new Buffer(new byte[capacity]);
            this.buffer = buffer;
        }
        Util.readBytes(srcFile, buffer.hb, fileSize);
        int dataSize = buffer.getInt();
        long sum = buffer.getLong();
        dataEnd = DATA_START + dataSize;
        if (dataSize >= 0 && (dataSize <= fileSize - DATA_START)
                && sum == buffer.getChecksum(DATA_START, dataSize)
                && parseData() == 0) {
            checksum = sum;
            return true;
        }
        return false;
    }

    private boolean writeToABFile(Buffer buffer) {
        int fileLen = buffer.hb.length;
        File aFile = new File(path, name + A_SUFFIX);
        File bFile = new File(path, name + B_SUFFIX);
        try {
            if (!Util.makeFileIfNotExist(aFile) || !Util.makeFileIfNotExist(bFile)) {
                throw new Exception(OPEN_FILE_FAILED);
            }
            RandomAccessFile aAccessFile = new RandomAccessFile(aFile, "rw");
            RandomAccessFile bAccessFile = new RandomAccessFile(bFile, "rw");
            aAccessFile.setLength(fileLen);
            bAccessFile.setLength(fileLen);
            aChannel = aAccessFile.getChannel();
            bChannel = bAccessFile.getChannel();
            aBuffer = aChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileLen);
            aBuffer.order(ByteOrder.LITTLE_ENDIAN);
            bBuffer = bChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileLen);
            bBuffer.order(ByteOrder.LITTLE_ENDIAN);
            aBuffer.put(buffer.hb, 0, dataEnd);
            bBuffer.put(buffer.hb, 0, dataEnd);
            return true;
        } catch (Exception e) {
            error(e);
        }
        return false;
    }

    private void copyBuffer(MappedByteBuffer src, MappedByteBuffer des, int end) {
        if (src.capacity() != des.capacity()) {
            try {
                FileChannel channel = (des == bBuffer) ? bChannel : aChannel;
                MappedByteBuffer newBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, src.capacity());
                newBuffer.order(ByteOrder.LITTLE_ENDIAN);
                if (des == bBuffer) {
                    bBuffer = newBuffer;
                } else {
                    aBuffer = newBuffer;
                }
                des = newBuffer;
            } catch (IOException e) {
                error(e);
                toBlockingMode();
                return;
            }
        }
        src.rewind();
        des.rewind();
        src.limit(end);
        des.put(src);
        src.limit(src.capacity());
    }

    private int parseData() {
        Buffer buffer = this.buffer;
        buffer.position = DATA_START;
        try {
            while (buffer.position < dataEnd) {
                int start = buffer.position;
                byte info = buffer.get();
                byte type = (byte) (info & Type.TYPE_MASK);
                if (type < Type.BOOLEAN || type > Type.OBJECT) {
                    throw new Exception(PARSE_DATA_FAILED);
                }
                int keySize = buffer.get() & 0xFF;
                if (info < 0) {
                    buffer.position += keySize;
                    int valueSize = (type <= Type.DOUBLE) ? TYPE_SIZE[type] : buffer.getShort() & 0xFFFF;
                    this.buffer.position += valueSize;
                    countInvalid(start, this.buffer.position);
                    continue;
                }
                String key = buffer.getString(keySize);
                int pos = buffer.position;
                if (type <= Type.DOUBLE) {
                    switch (type) {
                        case Type.BOOLEAN:
                            data.put(key, new Container.BooleanContainer(pos, buffer.get() == 1));
                            break;
                        case Type.INT:
                            data.put(key, new Container.IntContainer(pos, buffer.getInt()));
                            break;
                        case Type.LONG:
                            data.put(key, new Container.LongContainer(pos, buffer.getLong()));
                            break;
                        case Type.FLOAT:
                            data.put(key, new Container.FloatContainer(pos, buffer.getFloat()));
                            break;
                        default:
                            data.put(key, new Container.DoubleContainer(pos, buffer.getDouble()));
                            break;
                    }
                } else {
                    int size = buffer.getShort() & 0xFFFF;
                    boolean external = (info & Type.EXTERNAL_MASK) != 0;
                    if (external && size != Util.NAME_SIZE) {
                        throw new IllegalStateException("name size not match");
                    }
                    switch (type) {
                        case Type.STRING:
                            String str = buffer.getString(size);
                            data.put(key, new Container.StringContainer(start, pos + 2, str, size, external));
                            break;
                        case Type.ARRAY:
                            Object value = external ? buffer.getString(size) : buffer.getBytes(size);
                            data.put(key, new Container.ArrayContainer(start, pos + 2, value, size, external));
                            break;
                        default:
                            if (external) {
                                String fileName = buffer.getString(size);
                                data.put(key, new Container.ObjectContainer(start, pos + 2, fileName, size, true));
                            } else {
                                int tagSize = buffer.get() & 0xFF;
                                String tag = buffer.getString(tagSize);
                                IEncoder encoder = encoderMap.get(tag);
                                int objectSize = size - (tagSize + 1);
                                if (objectSize < 0) {
                                    throw new Exception(PARSE_DATA_FAILED);
                                }
                                if (encoder != null) {
                                    try {
                                        Object obj = encoder.decode(buffer.hb, buffer.position, objectSize);
                                        if (obj != null) {
                                            data.put(key, new Container.ObjectContainer(start, pos + 2, obj, size, false));
                                        }
                                    } catch (Exception e) {
                                        error(e);
                                    }
                                } else {
                                    error("object with tag: " + tag + " without encoder");
                                }
                                buffer.position += objectSize;
                            }
                            break;
                    }
                }
            }
        } catch (Exception e) {
            warning(e);
            return -1;
        }
        if (buffer.position != dataEnd) {
            warning(new Exception(PARSE_DATA_FAILED));
            return -1;
        }
        return 0;
    }

    public synchronized boolean contains(String key) {
        return data.containsKey(key);
    }

    public synchronized boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public synchronized boolean getBoolean(String key, boolean defValue) {
        Container.BooleanContainer c = (Container.BooleanContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public synchronized int getInt(String key, int defValue) {
        Container.IntContainer c = (Container.IntContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public synchronized float getFloat(String key, float defValue) {
        Container.FloatContainer c = (Container.FloatContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public synchronized long getLong(String key) {
        Container.LongContainer c = (Container.LongContainer) data.get(key);
        return c == null ? 0L : c.value;
    }

    public synchronized long getLong(String key, long defValue) {
        Container.LongContainer c = (Container.LongContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public double getDouble(String key) {
        return getDouble(key, 0D);
    }

    public synchronized double getDouble(String key, double defValue) {
        Container.DoubleContainer c = (Container.DoubleContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public synchronized String getString(String key, String defValue) {
        Container.StringContainer c = (Container.StringContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value instanceof String) {
                    return (String) value;
                }
                String str = getStringFromFile(c);
                if (!str.isEmpty()) {
                    bigValueCache.put(key, str);
                }
                return str;
            }
            return (String) c.value;
        }
        return defValue;
    }

    private String getStringFromFile(Container.StringContainer c) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        try {
            if (cache != null) {
                return new String(cache, StandardCharsets.UTF_8);
            }
            byte[] bytes = Util.getBytes(new File(path + name, fileName));
            if (bytes != null) {
                return (bytes.length == 0) ? "" : new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            error(e);
        }
        return "";
    }

    public byte[] getArray(String key) {
        return getArray(key, EMPTY_ARRAY);
    }

    public synchronized byte[] getArray(String key, byte[] defValue) {
        Container.ArrayContainer c = (Container.ArrayContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value instanceof byte[]) {
                    return (byte[]) value;
                }
                byte[] bytes = getArrayFromFile(c);
                if (bytes != null && bytes.length != 0) {
                    bigValueCache.put(key, bytes);
                }
                return bytes;
            }
            return (byte[]) c.value;
        }
        return defValue;
    }

    private byte[] getArrayFromFile(Container.ArrayContainer c) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        if (cache != null) {
            return cache;
        }
        try {
            byte[] a = Util.getBytes(new File(path + name, fileName));
            return a != null ? a : EMPTY_ARRAY;
        } catch (Exception e) {
            error(e);
        }
        return EMPTY_ARRAY;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T getObject(String key) {
        Container.ObjectContainer c = (Container.ObjectContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value != null) {
                    return (T) value;
                }
                Object obj = getObjectFromFile(c);
                if (obj != null) {
                    bigValueCache.put(key, obj);
                }
                return (T) obj;
            }
            return (T) c.value;
        }
        return null;
    }

    private Object getObjectFromFile(Container.ObjectContainer c) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        try {
            byte[] bytes = cache != null ? cache : Util.getBytes(new File(path + name, fileName));
            if (bytes != null) {
                int tagSize = bytes[0] & 0xFF;
                String tag = new String(bytes, 1, tagSize, StandardCharsets.UTF_8);
                IEncoder encoder = encoderMap.get(tag);
                if (encoder != null) {
                    int offset = 1 + tagSize;
                    return encoder.decode(bytes, offset, bytes.length - offset);
                } else {
                    warning(new Exception("No encoder for tag:" + tag));
                }
            } else {
                warning(new Exception("Read object data failed"));
            }
        } catch (Exception e) {
            error(e);
        }
        return null;
    }

    public synchronized Set<String> getStringSet(String key) {
        return getObject(key);
    }

    public synchronized void putBoolean(String key, boolean value) {
        checkKey(key);
        Container.BooleanContainer c = (Container.BooleanContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, Type.BOOLEAN);
            int offset = buffer.position;
            buffer.put((byte) (value ? 1 : 0));
            updateChange();
            data.put(key, new Container.BooleanContainer(offset, value));
            checkIfCommit();
        } else if (c.value != value) {
            c.value = value;
            updateBoolean((byte) (value ? 1 : 0), c.offset);
            checkIfCommit();
        }
    }

    public synchronized void putInt(String key, int value) {
        checkKey(key);
        Container.IntContainer c = (Container.IntContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, Type.INT);
            int offset = buffer.position;
            buffer.putInt(value);
            updateChange();
            data.put(key, new Container.IntContainer(offset, value));
            checkIfCommit();
        } else if (c.value != value) {
            long sum = (value ^ c.value) & 0xFFFFFFFFL;
            c.value = value;
            updateInt32(value, sum, c.offset);
            checkIfCommit();
        }
    }

    public synchronized void putFloat(String key, float value) {
        checkKey(key);
        Container.FloatContainer c = (Container.FloatContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, Type.FLOAT);
            int offset = buffer.position;
            buffer.putInt(Float.floatToRawIntBits(value));
            updateChange();
            data.put(key, new Container.FloatContainer(offset, value));
            checkIfCommit();
        } else if (c.value != value) {
            int newValue = Float.floatToRawIntBits(value);
            long sum = (Float.floatToRawIntBits(c.value) ^ newValue) & 0xFFFFFFFFL;
            c.value = value;
            updateInt32(newValue, sum, c.offset);
            checkIfCommit();
        }
    }

    public synchronized void putLong(String key, long value) {
        checkKey(key);
        Container.LongContainer c = (Container.LongContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, Type.LONG);
            int offset = buffer.position;
            buffer.putLong(value);
            updateChange();
            data.put(key, new Container.LongContainer(offset, value));
            checkIfCommit();
        } else if (c.value != value) {
            long sum = value ^ c.value;
            c.value = value;
            updateInt64(value, sum, c.offset);
            checkIfCommit();
        }
    }

    public synchronized void putDouble(String key, double value) {
        checkKey(key);
        Container.DoubleContainer c = (Container.DoubleContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, Type.DOUBLE);
            int offset = buffer.position;
            buffer.putLong(Double.doubleToRawLongBits(value));
            updateChange();
            data.put(key, new Container.DoubleContainer(offset, value));
            checkIfCommit();
        } else if (c.value != value) {
            long newValue = Double.doubleToRawLongBits(value);
            long sum = Double.doubleToRawLongBits(c.value) ^ newValue;
            c.value = value;
            updateInt64(newValue, sum, c.offset);
            checkIfCommit();
        }
    }

    public synchronized void putString(String key, String value) {
        checkKey(key);
        if (value == null) {
            remove(key);
        } else {
            Container.StringContainer c = (Container.StringContainer) data.get(key);
            if (value.length() * 3 < INTERNAL_LIMIT) {
                // putString is a frequent operation,
                // so we make some redundant code to speed up putString method.
                fastPutString(key, value, c);
            } else {
                byte[] bytes = value.isEmpty() ? EMPTY_ARRAY : value.getBytes(StandardCharsets.UTF_8);
                addOrUpdate(key, value, bytes, c, Type.STRING);
            }
        }
    }

    public synchronized void putArray(String key, byte[] value) {
        checkKey(key);
        if (value == null) {
            remove(key);
        } else {
            Container.ArrayContainer c = (Container.ArrayContainer) data.get(key);
            addOrUpdate(key, value, value, c, Type.ARRAY);
        }
    }

    /**
     * @param key     The name of the data to modify
     * @param value   The new value
     * @param encoder The encoder to encode value to byte[], encoder must register in  Builder.encoder(),
     *                for decoding byte[] to object in next loading.
     * @param <T>     Type of value
     */
    public synchronized <T> void putObject(String key, T value, IEncoder<T> encoder) {
        checkKey(key);
        if (encoder == null) {
            throw new IllegalArgumentException("Encoder is null");
        }
        String tag = encoder.tag();
        if (tag == null || tag.isEmpty() || tag.length() > 50) {
            throw new IllegalArgumentException("Invalid encoder tag:" + tag);
        }
        if (!encoderMap.containsKey(tag)) {
            throw new IllegalArgumentException("Encoder hasn't been registered");
        }

        if (value == null) {
            remove(key);
            return;
        }
        byte[] obj = null;
        try {
            obj = encoder.encode(value);
        } catch (Exception e) {
            error(e);
        }
        if (obj == null) {
            remove(key);
            return;
        }

        // assemble object bytes
        int tagSize = Buffer.getStringSize(tag);
        Buffer buffer = new Buffer(1 + tagSize + obj.length);
        buffer.put((byte) tagSize);
        buffer.putString(tag);
        buffer.putBytes(obj);
        byte[] bytes = buffer.hb;

        Container.ObjectContainer c = (Container.ObjectContainer) data.get(key);
        addOrUpdate(key, value, bytes, c, Type.OBJECT);
    }

    public synchronized void putStringSet(String key, Set<String> set) {
        if (set == null) {
            remove(key);
        } else {
            putObject(key, set, StringSetEncoder.INSTANCE);
        }
    }

    public synchronized void remove(String key) {
        Container.BaseContainer container = data.get(key);
        if (container != null) {
            final String oldFileName;
            data.remove(key);
            bigValueCache.remove(key);
            byte type = container.getType();
            if (type <= Type.DOUBLE) {
                int keySize = Buffer.getStringSize(key);
                int start = container.offset - (2 + keySize);
                remove(type, start, container.offset + TYPE_SIZE[type]);
                oldFileName = null;
            } else {
                Container.VarContainer c = (Container.VarContainer) container;
                remove(type, c.start, c.offset + c.valueSize);
                oldFileName = c.external ? (String) c.value : null;
            }
            byte newByte = (byte) (type | Type.DELETE_MASK);
            if (writingMode == NON_BLOCKING) {
                aBuffer.putLong(4, checksum);
                aBuffer.put(removeStart, newByte);
                bBuffer.putLong(4, checksum);
                bBuffer.put(removeStart, newByte);
            } else {
                buffer.putLong(4, checksum);
            }
            removeStart = 0;
            if (oldFileName != null) {
                if (writingMode == NON_BLOCKING) {
                    Config.getExecutor().execute(() -> Util.deleteFile(new File(path + name, oldFileName)));
                } else {
                    deletedFiles.add(oldFileName);
                }
            }
            checkGC();
            checkIfCommit();
        }
    }

    public synchronized void clear() {
        resetData();
        if (writingMode != NON_BLOCKING) {
            deleteCFiles();
        }
    }

    public synchronized Map<String, Object> getAll() {
        int size = data.size();
        if (size == 0) {
            return new HashMap<>();
        }
        Map<String, Object> result = new HashMap<>(size * 4 / 3 + 1);
        for (Map.Entry<String, Container.BaseContainer> entry : data.entrySet()) {
            String key = entry.getKey();
            Container.BaseContainer c = entry.getValue();
            Object value = null;
            switch (c.getType()) {
                case Type.BOOLEAN:
                    value = ((Container.BooleanContainer) c).value;
                    break;
                case Type.INT:
                    value = ((Container.IntContainer) c).value;
                    break;
                case Type.FLOAT:
                    value = ((Container.FloatContainer) c).value;
                    break;
                case Type.LONG:
                    value = ((Container.LongContainer) c).value;
                    break;
                case Type.DOUBLE:
                    value = ((Container.DoubleContainer) c).value;
                    break;
                case Type.STRING:
                    Container.StringContainer sc = (Container.StringContainer) c;
                    value = sc.external ? getStringFromFile(sc) : sc.value;
                    break;
                case Type.ARRAY:
                    Container.ArrayContainer ac = (Container.ArrayContainer) c;
                    value = ac.external ? getArrayFromFile(ac) : ac.value;
                    break;
                case Type.OBJECT:
                    Container.ObjectContainer oc = (Container.ObjectContainer) c;
                    value = oc.external ? getObjectFromFile(oc) : ((Container.ObjectContainer) c).value;
                    break;
            }
            result.put(key, value);
        }
        return result;
    }

    public void putAll(Map<String, Object> values) {
        putAll(values, null);
    }

    /**
     * Batch put objects.
     * Only support type in [boolean, int, long, float, double, String, byte[], Set of String] and object with encoder.
     *
     * @param values   map of key to value
     * @param encoders map of value Class to Encoder
     */
    public synchronized void putAll(Map<String, Object> values, Map<Class, IEncoder> encoders) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && !key.isEmpty()) {
                if (value instanceof String) {
                    putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    putFloat(key, (Float) value);
                } else if (value instanceof Double) {
                    putDouble(key, (Double) value);
                } else if (value instanceof Set) {
                    Set set = (Set) value;
                    if (!set.isEmpty() && set.iterator().next() instanceof String) {
                        //noinspection unchecked
                        putStringSet(key, (Set<String>) value);
                    }
                } else if (value instanceof byte[]) {
                    putArray(key, (byte[]) value);
                } else {
                    if (encoders != null) {
                        IEncoder encoder = encoders.get(value.getClass());
                        if (encoder != null) {
                            //noinspection unchecked
                            putObject(key, value, encoder);
                        } else {
                            warning(new Exception("missing encoder for type:" + value.getClass()));
                        }
                    } else {
                        warning(new Exception("missing encoders"));
                    }
                }
            }
        }
    }

    /**
     * Forces any changes to be written to the storage device containing the mapped file.
     * No need to call this unless what's had written is very import.
     * The system crash or power off before data syncing to disk might make recently update loss.
     */
    public synchronized void force() {
        if (writingMode == NON_BLOCKING) {
            aBuffer.force();
            bBuffer.force();
        }
    }

    /**
     * When you open file with mode of SYNC_BLOCKING or ASYNC_BLOCKING,
     * It will auto commit after every putting or removing, by default.
     * If you need to batch update several key-values, you could call this method at first,
     * and call {@link #commit()} after updating, that method will recover {@link #autoCommit} to 'true' again.
     */
    public synchronized void disableAutoCommit() {
        this.autoCommit = false;
    }

    public synchronized boolean commit() {
        autoCommit = true;
        return commitToCFile();
    }

    private void checkIfCommit() {
        if (writingMode != NON_BLOCKING && autoCommit) {
            commitToCFile();
        }
    }

    private boolean commitToCFile() {
        if (writingMode == ASYNC_BLOCKING) {
            applyExecutor.execute(this::writeToCFile);
        } else if (writingMode == SYNC_BLOCKING) {
            return writeToCFile();
        }
        return true;
    }

    private synchronized boolean writeToCFile() {
        try {
            File tmpFile = new File(path, name + TEMP_SUFFIX);
            if (Util.makeFileIfNotExist(tmpFile)) {
                RandomAccessFile accessFile = new RandomAccessFile(tmpFile, "rw");
                accessFile.setLength(dataEnd);
                accessFile.write(buffer.hb, 0, dataEnd);
                accessFile.close();
                File cFile = new File(path, name + C_SUFFIX);
                if (!cFile.exists() || cFile.delete()) {
                    if (tmpFile.renameTo(cFile)) {
                        clearDeletedFiles();
                        return true;
                    } else {
                        warning(new Exception("rename failed"));
                    }
                }
            }
        } catch (Exception e) {
            error(e);
        }
        return false;
    }

    private void clearDeletedFiles() {
        if (!deletedFiles.isEmpty()) {
            for (String oldFileName : deletedFiles) {
                Config.getExecutor().execute(() -> Util.deleteFile(new File(path + name, oldFileName)));
            }
            deletedFiles.clear();
        }
    }

    private void deleteCFiles() {
        try {
            Util.deleteFile(new File(path, name + C_SUFFIX));
            Util.deleteFile(new File(path, name + TEMP_SUFFIX));
        } catch (Exception e) {
            error(e);
        }
    }

    private void toBlockingMode() {
        writingMode = ASYNC_BLOCKING;
        Util.closeQuietly(aChannel);
        Util.closeQuietly(bChannel);
        aChannel = null;
        bChannel = null;
        aBuffer = null;
        bBuffer = null;
    }

    private void resetData() {
        if (writingMode == NON_BLOCKING) {
            try {
                resetBuffer(aBuffer);
                resetBuffer(bBuffer);
            } catch (IOException e) {
                toBlockingMode();
            }
        }
        clearData();
        Util.deleteFile(new File(path + name));
    }

    private void resetBuffer(MappedByteBuffer buffer) throws IOException {
        if (buffer.capacity() != PAGE_SIZE) {
            FileChannel channel = buffer == aBuffer ? aChannel : bChannel;
            channel.truncate(PAGE_SIZE);
            MappedByteBuffer newBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, PAGE_SIZE);
            newBuffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer == aBuffer) {
                aBuffer = newBuffer;
            } else {
                bBuffer = newBuffer;
            }
            buffer = newBuffer;
        }
        buffer.putInt(0, 0);
        buffer.putLong(4, 0L);
    }

    private void clearData() {
        dataEnd = DATA_START;
        checksum = 0L;
        clearInvalid();
        data.clear();
        bigValueCache.clear();
        externalCache.clear();
        if (buffer == null || buffer.hb.length != PAGE_SIZE) {
            buffer = new Buffer(PAGE_SIZE);
        } else {
            buffer.putInt(0, 0);
            buffer.putLong(4, 0L);
        }
    }

    private void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key is empty");
        }
    }

    private void checkKeySize(int keySize) {
        if (keySize > 0xFF) {
            throw new IllegalArgumentException("key's length must less than 256");
        }
    }

    private void wrapHeader(String key, byte type) {
        wrapHeader(key, type, TYPE_SIZE[type]);
    }

    private void wrapHeader(String key, byte type, int valueSize) {
        int keySize = Buffer.getStringSize(key);
        checkKeySize(keySize);
        updateSize = 2 + keySize + valueSize;
        preparePutBytes();
        buffer.put(type);
        putKey(key, keySize);
    }

    private void updateChange() {
        checksum ^= buffer.getChecksum(updateStart, updateSize);
        if (writingMode == NON_BLOCKING) {
            // When size of changed data is more than 8 bytes,
            // checksum might fail to check the integrity in small probability.
            // So we make the dataLen to be negative,
            // if crash happen when writing data to mmap memory,
            // we can know that the writing had not accomplished.
            aBuffer.putInt(0, -1);
            syncABBuffer(aBuffer);
            aBuffer.putInt(0, dataEnd - DATA_START);

            // bBuffer doesn't need to mark dataLen's part before writing bytes,
            // cause aBuffer has already written completely.
            // We just need to have one file to be integrated at least at any time.
            syncABBuffer(bBuffer);
        } else {
            if (sizeChanged) {
                buffer.putInt(0, dataEnd - DATA_START);
            }
            buffer.putLong(4, checksum);
        }
        sizeChanged = false;
        removeStart = 0;
        updateSize = 0;
    }

    private void syncABBuffer(MappedByteBuffer buffer) {
        if (sizeChanged && buffer != aBuffer) {
            buffer.putInt(0, dataEnd - DATA_START);
        }
        buffer.putLong(4, checksum);
        if (removeStart != 0) {
            buffer.put(removeStart, this.buffer.hb[removeStart]);
        }
        if (updateSize != 0) {
            buffer.position(updateStart);
            buffer.put(this.buffer.hb, updateStart, updateSize);
        }
    }

    private int bytesThreshold() {
        if (dataEnd <= (1 << 14)) {
            return BASE_GC_BYTES_THRESHOLD;
        } else {
            return dataEnd <= (1 << 16) ? BASE_GC_BYTES_THRESHOLD << 1 : BASE_GC_BYTES_THRESHOLD << 2;
        }
    }

    private void ensureSize(int allocate) {
        int capacity = buffer.hb.length;
        int expected = dataEnd + allocate;
        if (expected >= capacity) {
            if (invalidBytes > allocate && invalidBytes > bytesThreshold()) {
                gc(allocate);
            } else {
                int newCapacity = getNewCapacity(capacity, expected);
                byte[] bytes = new byte[newCapacity];
                System.arraycopy(buffer.hb, 0, bytes, 0, dataEnd);
                buffer.hb = bytes;
                if (writingMode == NON_BLOCKING) {
                    try {
                        aBuffer = aChannel.map(FileChannel.MapMode.READ_WRITE, 0, newCapacity);
                        aBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        bBuffer = bChannel.map(FileChannel.MapMode.READ_WRITE, 0, newCapacity);
                        bBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    } catch (IOException e) {
                        error(new Exception(MAP_FAILED, e));
                        buffer.putInt(0, dataEnd - DATA_START);
                        buffer.putLong(4, checksum);
                        toBlockingMode();
                    }
                }
            }
        }
    }

    private long shiftCheckSum(long checkSum, int offset) {
        int shift = (offset & 7) << 3;
        return (checkSum << shift) | (checkSum >>> (64 - shift));
    }

    private void updateBoolean(byte value, int offset) {
        checksum ^= shiftCheckSum(1L, offset);
        if (writingMode == NON_BLOCKING) {
            aBuffer.putLong(4, checksum);
            aBuffer.put(offset, value);
            bBuffer.putLong(4, checksum);
            bBuffer.put(offset, value);
        } else {
            buffer.putLong(4, checksum);
        }
        buffer.hb[offset] = value;
    }

    private void updateInt32(int value, long sum, int offset) {
        checksum ^= shiftCheckSum(sum, offset);
        if (writingMode == NON_BLOCKING) {
            aBuffer.putLong(4, checksum);
            aBuffer.putInt(offset, value);
            bBuffer.putLong(4, checksum);
            bBuffer.putInt(offset, value);
        } else {
            buffer.putLong(4, checksum);
        }
        buffer.putInt(offset, value);
    }

    private void updateInt64(long value, long sum, int offset) {
        checksum ^= shiftCheckSum(sum, offset);
        if (writingMode == NON_BLOCKING) {
            aBuffer.putLong(4, checksum);
            aBuffer.putLong(offset, value);
            bBuffer.putLong(4, checksum);
            bBuffer.putLong(offset, value);
        } else {
            buffer.putLong(4, checksum);
        }
        buffer.putLong(offset, value);
    }

    private void updateBytes(int offset, byte[] bytes) {
        int size = bytes.length;
        checksum ^= buffer.getChecksum(offset, size);
        buffer.position = offset;
        buffer.putBytes(bytes);
        checksum ^= buffer.getChecksum(offset, size);
        if (writingMode == NON_BLOCKING) {
            aBuffer.putInt(0, -1);
            aBuffer.putLong(4, checksum);
            aBuffer.position(offset);
            aBuffer.put(bytes);
            aBuffer.putInt(0, dataEnd - DATA_START);
            bBuffer.putLong(4, checksum);
            bBuffer.position(offset);
            bBuffer.put(bytes);
        } else {
            buffer.putLong(4, checksum);
        }
    }

    private void preparePutBytes() {
        ensureSize(updateSize);
        updateStart = dataEnd;
        dataEnd += updateSize;
        buffer.position = updateStart;
        sizeChanged = true;
    }

    private void putKey(String key, int keySize) {
        buffer.put((byte) keySize);
        if (keySize == key.length()) {
            //noinspection deprecation
            key.getBytes(0, keySize, buffer.hb, buffer.position);
            buffer.position += keySize;
        } else {
            buffer.putString(key);
        }
    }

    private void putStringValue(String value, int valueSize) {
        buffer.putShort((short) valueSize);
        if (valueSize == value.length()) {
            //noinspection deprecation
            value.getBytes(0, valueSize, buffer.hb, buffer.position);
        } else {
            buffer.putString(value);
        }
    }

    private void fastPutString(String key, String value, Container.StringContainer c) {
        int stringLen = Buffer.getStringSize(value);
        if (c == null) {
            int keyLen = Buffer.getStringSize(key);
            checkKeySize(keyLen);
            // 4 bytes = type:1, keyLen: 1, stringLen:2
            // preSize include size of [type|keyLen|key|stringLen], which is "4+lengthOf(key)"
            int preSize = 4 + keyLen;
            updateSize = preSize + stringLen;
            preparePutBytes();
            buffer.put(Type.STRING);
            putKey(key, keyLen);
            putStringValue(value, stringLen);
            data.put(key, new Container.StringContainer(updateStart, updateStart + preSize, value, stringLen, false));
            updateChange();
        } else {
            final String oldFileName;
            boolean needCheckGC = false;
            if (c.valueSize == stringLen) {
                checksum ^= buffer.getChecksum(c.offset, c.valueSize);
                if (stringLen == value.length()) {
                    //noinspection deprecation
                    value.getBytes(0, stringLen, buffer.hb, c.offset);
                } else {
                    buffer.position = c.offset;
                    buffer.putString(value);
                }
                updateStart = c.offset;
                updateSize = stringLen;
                oldFileName = null;
            } else {
                // preSize: bytes count from start to value offset
                int preSize = c.offset - c.start;
                updateSize = preSize + stringLen;
                preparePutBytes();
                buffer.put(Type.STRING);
                int keyBytes = preSize - 3;
                System.arraycopy(buffer.hb, c.start + 1, buffer.hb, buffer.position, keyBytes);
                buffer.position += keyBytes;
                putStringValue(value, stringLen);

                remove(Type.STRING, c.start, c.offset + c.valueSize);
                needCheckGC = true;
                oldFileName = c.external ? (String) c.value : null;

                c.external = false;
                c.start = updateStart;
                c.offset = updateStart + preSize;
                c.valueSize = stringLen;
            }
            c.value = value;
            updateChange();
            if (needCheckGC) {
                checkGC();
            }
            if (oldFileName != null) {
                if (writingMode == NON_BLOCKING) {
                    Config.getExecutor().execute(() -> Util.deleteFile(new File(path + name, oldFileName)));
                } else {
                    deletedFiles.add(oldFileName);
                }
            }
        }
        checkIfCommit();
    }

    private void addOrUpdate(String key, Object value, byte[] bytes, Container.VarContainer c, byte type) {
        if (c == null) {
            addObject(key, value, bytes, type);
        } else {
            if (!c.external && c.valueSize == bytes.length) {
                updateBytes(c.offset, bytes);
                c.value = value;
            } else {
                updateObject(key, value, bytes, c);
            }
        }
        checkIfCommit();
    }

    private void addObject(String key, Object value, byte[] bytes, byte type) {
        int offset = saveArray(key, bytes, type);
        if (offset != 0) {
            int size;
            Object v;
            boolean external = tempExternalName != null;
            if (external) {
                bigValueCache.put(key, value);
                size = Util.NAME_SIZE;
                v = tempExternalName;
                tempExternalName = null;
            } else {
                size = bytes.length;
                v = value;
            }
            Container.BaseContainer c;
            if (type == Type.STRING) {
                c = new Container.StringContainer(updateStart, offset, (String) v, size, external);
            } else if (type == Type.ARRAY) {
                c = new Container.ArrayContainer(updateStart, offset, v, size, external);
            } else {
                c = new Container.ObjectContainer(updateStart, offset, v, size, external);
            }
            data.put(key, c);
            updateChange();
        }
    }

    private void updateObject(String key, Object value, byte[] bytes, Container.VarContainer c) {
        int offset = saveArray(key, bytes, c.getType());
        if (offset != 0) {
            String oldFileName = c.external ? (String) c.value : null;
            remove(c.getType(), c.start, c.offset + c.valueSize);
            boolean external = tempExternalName != null;
            c.start = updateStart;
            c.offset = offset;
            c.external = external;
            if (external) {
                bigValueCache.put(key, value);
                c.value = tempExternalName;
                c.valueSize = Util.NAME_SIZE;
                tempExternalName = null;
            } else {
                c.value = value;
                c.valueSize = bytes.length;
            }
            updateChange();
            checkGC();
            if (oldFileName != null) {
                if (writingMode == NON_BLOCKING) {
                    Config.getExecutor().execute(() -> Util.deleteFile(new File(path + name, oldFileName)));
                } else {
                    deletedFiles.add(oldFileName);
                }
            }
        }
    }

    private int saveArray(String key, byte[] value, byte type) {
        tempExternalName = null;
        if (value.length < INTERNAL_LIMIT) {
            return wrapArray(key, value, type);
        } else {
            String fileName = Util.randomName();
            info("save large value, key:" + key + ", size:" + value.length + ", fileName:" + fileName);
            File file = new File(path + name, fileName);
            externalCache.put(fileName, value);
            externalExecutor.execute(key, () -> Util.saveBytes(file, value));
            tempExternalName = fileName;
            byte[] fileNameBytes = new byte[Util.NAME_SIZE];
            // noinspection deprecation
            fileName.getBytes(0, Util.NAME_SIZE, fileNameBytes, 0);
            return wrapArray(key, fileNameBytes, (byte) (type | Type.EXTERNAL_MASK));
        }
    }

    private int wrapArray(String key, byte[] value, byte type) {
        wrapHeader(key, type, 2 + value.length);
        buffer.putShort((short) value.length);
        int offset = buffer.position;
        buffer.putBytes(value);
        return offset;
    }

    private void remove(byte type, int start, int end) {
        countInvalid(start, end);
        byte newByte = (byte) (type | Type.DELETE_MASK);
        byte oldByte = buffer.hb[start];
        int shift = (start & 7) << 3;
        checksum ^= ((long) (newByte ^ oldByte) & 0xFF) << shift;
        buffer.hb[start] = newByte;
        removeStart = start;
    }

    private void checkGC() {
        if (invalidBytes >= (bytesThreshold() << 1)
                || invalids.size() >= (dataEnd < (1 << 14) ? BASE_GC_KEYS_THRESHOLD : BASE_GC_KEYS_THRESHOLD << 1)) {
            gc(0);
        }
    }

    private void mergeInvalids() {
        int i = invalids.size() - 1;
        Segment p = invalids.get(i);
        while (i > 0) {
            Segment q = invalids.get(--i);
            if (p.start == q.end) {
                q.end = p.end;
                invalids.remove(i + 1);
            }
            p = q;
        }
    }

    void gc(int allocate) {
        Collections.sort(invalids);
        mergeInvalids();

        final Segment head = invalids.get(0);
        final int gcStart = head.start;
        final int newDataEnd = dataEnd - invalidBytes;
        final int newDataSize = newDataEnd - DATA_START;
        final int updateSize = newDataEnd - gcStart;
        final int gcSize = dataEnd - gcStart;
        final boolean fullChecksum = newDataSize < gcSize + updateSize;
        if (!fullChecksum) {
            checksum ^= buffer.getChecksum(gcStart, gcSize);
        }
        // compact and record shift
        int n = invalids.size();
        final int remain = dataEnd - invalids.get(n - 1).end;
        int shiftCount = (remain > 0) ? n : n - 1;
        int[] srcToShift = new int[shiftCount << 1];
        int desPos = head.start;
        int srcPos = head.end;
        for (int i = 1; i < n; i++) {
            Segment q = invalids.get(i);
            int size = q.start - srcPos;
            System.arraycopy(buffer.hb, srcPos, buffer.hb, desPos, size);
            int index = (i - 1) << 1;
            srcToShift[index] = srcPos;
            srcToShift[index + 1] = srcPos - desPos;
            desPos += size;
            srcPos = q.end;
        }
        if (remain > 0) {
            System.arraycopy(buffer.hb, srcPos, buffer.hb, desPos, remain);
            int index = (n - 1) << 1;
            srcToShift[index] = srcPos;
            srcToShift[index + 1] = srcPos - desPos;
        }
        clearInvalid();

        if (fullChecksum) {
            checksum = buffer.getChecksum(DATA_START, newDataEnd - DATA_START);
        } else {
            checksum ^= buffer.getChecksum(gcStart, newDataEnd - gcStart);
        }
        dataEnd = newDataEnd;

        if (writingMode == NON_BLOCKING) {
            aBuffer.putInt(0, -1);
            aBuffer.putLong(4, checksum);
            aBuffer.position(gcStart);
            aBuffer.put(buffer.hb, gcStart, updateSize);
            aBuffer.putInt(0, newDataSize);
            bBuffer.putInt(0, newDataSize);
            bBuffer.putLong(4, checksum);
            bBuffer.position(gcStart);
            bBuffer.put(buffer.hb, gcStart, updateSize);
        } else {
            buffer.putInt(0, newDataSize);
            buffer.putLong(4, checksum);
        }

        updateOffset(gcStart, srcToShift);
        int expectedEnd = newDataEnd + allocate;
        if (buffer.hb.length - expectedEnd > TRUNCATE_THRESHOLD) {
            truncate(expectedEnd);
        }
        info(GC_FINISH);
    }

    private void updateOffset(int gcStart, int[] srcToShift) {
        Collection<Container.BaseContainer> values = data.values();
        for (Container.BaseContainer c : values) {
            if (c.offset > gcStart) {
                int index = Util.binarySearch(srcToShift, c.offset);
                int shift = srcToShift[(index << 1) + 1];
                c.offset -= shift;
                if (c.getType() >= Type.STRING) {
                    ((Container.VarContainer) c).start -= shift;
                }
            }
        }
    }

    private void truncate(int expectedEnd) {
        // reserve at least one page space
        int newCapacity = getNewCapacity(PAGE_SIZE, expectedEnd + PAGE_SIZE);
        if (newCapacity >= buffer.hb.length) {
            return;
        }
        byte[] bytes = new byte[newCapacity];
        System.arraycopy(buffer.hb, 0, bytes, 0, dataEnd);
        buffer.hb = bytes;
        if (writingMode == NON_BLOCKING) {
            try {
                aChannel.truncate(newCapacity);
                aBuffer = aChannel.map(FileChannel.MapMode.READ_WRITE, 0, newCapacity);
                aBuffer.order(ByteOrder.LITTLE_ENDIAN);
                bChannel.truncate(newCapacity);
                bBuffer = bChannel.map(FileChannel.MapMode.READ_WRITE, 0, newCapacity);
                bBuffer.order(ByteOrder.LITTLE_ENDIAN);
            } catch (IOException e) {
                error(new Exception(MAP_FAILED, e));
                toBlockingMode();
            }
        }
        info(TRUNCATE_FINISH);
    }

    private int getNewCapacity(int capacity, int expected) {
        if (expected > DATA_SIZE_LIMIT) {
            throw new IllegalStateException("data size out of limit");
        }
        if (expected <= PAGE_SIZE) {
            return PAGE_SIZE;
        } else {
            while (capacity < expected) {
                if (capacity <= DOUBLE_LIMIT) {
                    capacity <<= 1;
                } else {
                    capacity += DOUBLE_LIMIT;
                }
            }
            return capacity;
        }
    }

    private void countInvalid(int start, int end) {
        invalidBytes += (end - start);
        invalids.add(new Segment(start, end));
    }

    private void clearInvalid() {
        invalidBytes = 0;
        invalids.clear();
    }

    private static class Segment implements Comparable<Segment> {
        int start;
        int end;

        Segment(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public int compareTo(Segment o) {
            return start - o.start;
        }
    }

    private void error(String message) {
        if (logger != null) {
            logger.e(name, new Exception(message));
        }
    }

    private void error(Exception e) {
        if (logger != null) {
            logger.e(name, e);
        }
    }

    private void warning(Exception e) {
        if (logger != null) {
            logger.w(name, e);
        }
    }

    private void info(String message) {
        if (logger != null) {
            logger.i(name, message);
        }
    }

    // All params of Logger && Encoder are not null.

    public static class Builder {
        private final Map<String, KVDB> INSTANCE_MAP = new ConcurrentHashMap<>();
        private final String path;
        private final String name;
        private IEncoder[] encoders;
        private int writingMode = NON_BLOCKING;

        public Builder(String path, String name) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("path is empty");
            }
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name is empty");
            }
            this.path = path.endsWith("/") ? path : (path + '/');
            this.name = name;
        }

        /**
         * Set obj Encoders
         *
         * @param encoders The encoder array to decode the bytes to obj.
         * @return the builder
         */
        public Builder encoder(IEncoder[] encoders) {
            this.encoders = encoders;
            return this;
        }

        /**
         * Assigned writing mode to SYNC_BLOCKING.
         * <p>
         * In non-blocking mode (write data with mmap),
         * it might loss update if the system crash or power off before flush data to disk.
         * You could use {@link #force()} to avoid loss update, or use SYNC_BLOCKING mode.
         * <p>
         * In blocking mode, every update will write all data to the file, which is expensive cost.
         * <p>
         * So it's recommended to use blocking mode only if the data is every important.
         * <p>
         *
         * @return the builder
         */
        public Builder blocking() {
            writingMode = SYNC_BLOCKING;
            return this;
        }

        /**
         * Similar to {@link #blocking()}, but put writing task to async thread.
         *
         * @return the builder
         */
        public Builder asyncBlocking() {
            writingMode = ASYNC_BLOCKING;
            return this;
        }

        public KVDB build() {
            String key = path + name;
            KVDB kv = INSTANCE_MAP.get(key);
            if (kv == null) {
                synchronized (Builder.class) {
                    kv = INSTANCE_MAP.get(key);
                    if (kv == null) {
                        kv = new KVDB(path, name, encoders, writingMode);
                        INSTANCE_MAP.put(key, kv);
                    }
                }
            }
            return kv;
        }
    }

    @Override
    public synchronized String toString() {
        return "KVDB: path:" + path + " name:" + name;
    }
}
