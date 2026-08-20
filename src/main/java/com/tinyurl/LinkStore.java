package com.tinyurl;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stockage en mémoire des liens. Thread-safe grâce à ConcurrentHashMap.
 * Génère aussi les codes courts (base62, 6 caractères → 56 milliards de combinaisons).
 */
public class LinkStore {

    private static final String ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;

    private final Map<String, Link> store = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Crée un nouveau lien avec un code généré aléatoirement.
     * Retente en cas de collision (extrêmement rare avec 62^6 combinaisons).
     */
    public Link save(String url) {
        String code;
        do {
            code = generateCode();
        } while (store.containsKey(code));

        Link link = new Link(code, url, Instant.now(), new AtomicLong(0));
        store.put(code, link);
        return link;
    }

    public Optional<Link> findByCode(String code) {
        return Optional.ofNullable(store.get(code));
    }

    public List<Link> findAll() {
        return List.copyOf(store.values());
    }

    public boolean delete(String code) {
        return store.remove(code) != null;
    }

    /**
     * Incrémente le compteur de hits pour un code.
     * Utilisé lors des redirections.
     */
    public void incrementHits(String code) {
        Link link = store.get(code);
        if (link != null) {
            link.hits().incrementAndGet();
        }
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}