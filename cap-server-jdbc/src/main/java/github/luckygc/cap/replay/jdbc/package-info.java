/**
 * JDBC 防重放存储。
 *
 * <p>每次消费必须使用初始处于自动提交模式的独立连接，不能加入调用方事务。
 */
@NullMarked
package github.luckygc.cap.replay.jdbc;

import org.jspecify.annotations.NullMarked;
