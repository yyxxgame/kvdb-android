package cn.yyxx.kvdb;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
interface ILogger {
    void i(String name, String message);

    void w(String name, Exception e);

    void e(String name, Exception e);
}
