package github.luckygc.cap.impl;

import github.luckygc.cap.CapStore;
import github.luckygc.cap.model.CapToken;
import github.luckygc.cap.model.ChallengeData;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryCapStore implements CapStore {

    private final Map<String, ChallengeData> challengeDataMap = new ConcurrentHashMap<>(100);
    private final Map<String, CapToken> capTokenMap = new ConcurrentHashMap<>(100);

    private final AtomicBoolean cleaningFlag = new AtomicBoolean(false);

    @Override
    public void cleanExpiredTokens() {
        if (!cleaningFlag.compareAndSet(false, true)) {
            return;
        }

        new Thread(() -> {
            try {
                challengeDataMap.values().stream()
                        .filter(challengeData -> isExpired(challengeData.expires()))
                        .forEach(this::deleteChallengeData);

                capTokenMap.values().stream()
                        .filter(challengeData -> isExpired(challengeData.expires()))
                        .forEach(this::deleteCapToken);
            } finally {
                cleaningFlag.set(false);
            }
        }).start();
    }

    private void deleteCapToken(CapToken capToken) {
        capTokenMap.remove(capToken.token());
    }

    private boolean isExpired(long expires) {
        return expires < System.currentTimeMillis();
    }

    @Override
    public void saveChallengeData(ChallengeData challengeData) {
        challengeDataMap.put(challengeData.token(), challengeData);
    }

    @Override
    public Optional<ChallengeData> findChallengeData(String token) {
        return Optional.ofNullable(challengeDataMap.get(token));
    }

    @Override
    public void deleteChallengeData(ChallengeData challengeData) {
        challengeDataMap.remove(challengeData.token());
    }

    @Override
    public void saveCapToken(CapToken capToken) {
        capTokenMap.put(capToken.token(), capToken);
    }

    @Override
    public Optional<CapToken> findCapToken(String token) {
        return Optional.ofNullable(capTokenMap.get(token));
    }
}
