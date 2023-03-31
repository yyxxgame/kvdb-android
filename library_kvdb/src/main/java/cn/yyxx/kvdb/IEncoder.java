package cn.yyxx.kvdb;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
public interface IEncoder<T> {
    String tag();

    byte[] encode(T obj);

    // 'bytes' is not null (The caller had checked)
    T decode(byte[] bytes, int offset, int length);
}
