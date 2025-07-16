# CAP Server

> https://github.com/tiagorangel1/cap 项目server模块的java实现

## 快速开始

### 依赖要求

- Java 17+

### 基本使用

maven, 版本与 [cap](https://github.com/tiagorangel1/cap) server模块版本一致
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
<version>2.0.0</version>
</dependency>
```

```java
// 创建 CAP 管理器，生产环境建议实现自己的CapStore
CapManager capManager = new CapManagerImpl.Builder()
                .locale(Locale.CHINESE)
                .defaultChallengeConfig(new ChallengeConfig())
                .capStore(new MemoryCapStore())
                .build();

// 实现默认端点
@PostMapping("challenge")
public ChallengeDataDTO createChallenge() {
    return capManager.createChallenge();
}

@PostMapping("redeem")
public CapTokenDTO redeemChallenge(@RequestBody RedeemChallengeRequest redeemChallengeRequest) {
    return capManager.redeemChallenge(redeemChallengeRequest);
}

// 用于验证挑战成功返回给前端的token是否有效
capManager.validateCapToken(capToken);
```

## 许可证

Apache License 2.0
