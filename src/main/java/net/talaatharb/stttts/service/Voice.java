package net.talaatharb.stttts.service;

/** A built-in speaker of a text-to-speech model. */
public record Voice(String id, String name, String languageCode, String gender) {
    @Override
    public String toString() {
        return name;
    }
}
