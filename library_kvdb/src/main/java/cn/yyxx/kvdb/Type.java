package cn.yyxx.kvdb;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
class Type {
    public static final byte DELETE_MASK = (byte) 0x80;

    public static final byte EXTERNAL_MASK = 0x40;

    public static final byte TYPE_MASK = 0x3F;
    public static final byte BOOLEAN = 1;
    public static final byte INT = 2;
    public static final byte FLOAT = 3;
    public static final byte LONG = 4;
    public static final byte DOUBLE = 5;
    public static final byte STRING = 6;
    public static final byte ARRAY = 7;
    public static final byte OBJECT = 8;
}
