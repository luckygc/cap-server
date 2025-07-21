# CAP Server

> https://github.com/tiagorangel1/cap 项目server模块的java实现

## 快速开始

### 依赖要求

- Java 17+

### 基本使用

maven, 前三位版本与 [cap](https://github.com/tiagorangel1/cap) server模块版本一致

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
<groupId>com.github.luckygc</groupId>
<artifactId>cap-server</artifactId>
<version>2.0.0.1</version>
</dependency>
```
创建CapManager,生产环境建议实现自己的CapStore
```java
// 创建默认CapManager
CapManager capManager = CapManager.builder().build();

// 创建自定义配置CapManager
CapManager capManager2 = CapManager.builder()
        .store(new CustomStore())
        .challenge(c -> c.count(40).size(30).expireMs(60 * 1000L))
        .capToken(c -> c.expireMs(2 * 60 * 1000L))
        .build();
```
创建web接口,url最后部分必须是challenge或redeem
```java
// 实现默认端点
@PostMapping("challenge")
public ChallengeData createChallenge() {
    return capManager.createChallenge();
    // 或者 return capManager.createChallenge(challengeConfig);
}

@PostMapping("redeem")
public CapToken redeemChallenge(@RequestBody RedeemChallengeRequest redeemChallengeRequest) {
    return capManager.redeemChallenge(redeemChallengeRequest);
}
```

```java
// 用于验证挑战成功返回给前端的token是否有效
capManager.validateCapToken(capToken);
```
### 默认配置 

#### 默认存储配置
内存存储

#### 默认挑战配置  
数量: 50  
长度: 32  
难度: 4  
过期时间: 5分钟

#### 默认CapToken配置   
过期时间: 2分钟

## 许可证

Apache License 2.0
