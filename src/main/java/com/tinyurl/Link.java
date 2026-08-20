package com.tinyurl;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Un lien court : le code (ex: "aB3xY") pointe vers une URL cible.
 * hits est un compteur atomique — chaque redirection l'incrémente.
 */
public record Link(
    String code,
    String url,
    Instant createdAt,
    AtomicLong hits
) {}    