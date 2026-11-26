package com.exkor.flipbot.flipper.practice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Loads and saves a {@link PracticeBook} (practice-state.json) and appends its events to practice.jsonl. */
public final class PracticeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson LINE = new Gson();

    private final Path stateFile;
    private final Path eventFile;
    private PracticeBook book;

    public PracticeStore(Path dir) {
        this.stateFile = dir.resolve("practice-state.json");
        this.eventFile = dir.resolve("practice.jsonl");
    }

    public synchronized PracticeBook book() {
        if (book == null) {
            book = load();
            book.events = this::append;
        }
        return book;
    }

    /** Starts a new practice run; the old event log is kept. */
    public synchronized PracticeBook reset() {
        book = new PracticeBook();
        book.events = this::append;
        save();
        return book;
    }

    public synchronized void save() {
        if (book == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(book), StandardCharsets.UTF_8);
            Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[FlipBot] practice state not saved: " + e.getMessage());
        }
    }

    private PracticeBook load() {
        try {
            if (Files.exists(stateFile)) {
                PracticeBook loaded = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), PracticeBook.class);
                if (loaded != null) return loaded;
            }
        } catch (Exception e) {
            System.out.println("[FlipBot] practice state unreadable, starting fresh: " + e.getMessage());
        }
        return new PracticeBook();
    }

    private void append(java.util.Map<String, Object> event) {
        try {
            Files.createDirectories(eventFile.getParent());
            Files.writeString(eventFile, LINE.toJson(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[FlipBot] practice event not logged: " + e.getMessage());
        }
    }
}
