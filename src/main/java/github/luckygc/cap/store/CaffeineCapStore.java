package github.luckygc.cap.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import github.luckygc.cap.impl.CapManagerImpl;
import github.luckygc.cap.CapStore;
import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.model.CapToken;
import github.luckygc.cap.model.ChallengeData;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.Optional;

public class CaffeineCapStore implements CapStore {

    private final Cache<String, ChallengeData> challengeDataCache;
    private final Cache<String, CapToken> capTokenCache;

    public CaffeineCapStore(ChallengeConfig defaultConfig) {
        this.challengeDataCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(defaultConfig.getChallengeExpireMs() * 2)) // 自动过期5分钟
                .maximumSize(10000)
                .build();

        this.capTokenCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(CapManagerImpl.DEFAULT_CAP_TOKEN_EXPIRE_MS * 2))
                .maximumSize(10000)
                .build();
    }

    @Override
    public void cleanExpiredTokens() {
        long currentTime = System.currentTimeMillis();

        // 收集过期的ChallengeData token（基于expires字段）
        var expiredChallengeTokens = challengeDataCache.asMap().entrySet().stream()
                .filter(entry -> {
                    ChallengeData data = entry.getValue();
                    return data.expires() != null && data.expires() < currentTime;
                })
                .map(Entry::getKey)
                .toList();

        // 批量删除过期的ChallengeData
        expiredChallengeTokens.forEach(challengeDataCache::invalidate);

        // 收集过期的CapToken token（基于expires字段）
        var expiredCapTokens = capTokenCache.asMap().entrySet().stream()
                .filter(entry -> {
                    CapToken token = entry.getValue();
                    return token.expires() != null && token.expires() < currentTime;
                })
                .map(entry -> entry.getKey())
                .toList();

        // 批量删除过期的CapToken
        expiredCapTokens.forEach(capTokenCache::invalidate);

        // 触发Caffeine的自动清理
        challengeDataCache.cleanUp();
        capTokenCache.cleanUp();
    }

    @Override
    public void saveChallengeData(ChallengeData challengeData) {
        challengeDataCache.put(challengeData.token(), challengeData);
    }

    @Override
    public Optional<ChallengeData> findChallengeData(String token) {
        ChallengeData data = challengeDataCache.getIfPresent(token);
        if (data != null && data.expires() != null && data.expires() < System.currentTimeMillis()) {
            // 如果已过期，删除并返回空
            challengeDataCache.invalidate(token);
            return Optional.empty();
        }
        return Optional.ofNullable(data);
    }

    @Override
    public void deleteChallengeData(ChallengeData challengeData) {
        challengeDataCache.invalidate(challengeData.token());
    }

    @Override
    public void saveCapToken(CapToken capToken) {
        capTokenCache.put(capToken.token(), capToken);
    }

    @Override
    public Optional<CapToken> findCapToken(String token) {
        CapToken capToken = capTokenCache.getIfPresent(token);
        if (capToken != null && capToken.expires() != null && capToken.expires() < System.currentTimeMillis()) {
            // 如果已过期，删除并返回空
            capTokenCache.invalidate(token);
            return Optional.empty();
        }
        return Optional.ofNullable(capToken);
    }
} 
