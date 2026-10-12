package net.talaatharb.stttts.service;

/** A language a model supports, identified by ISO 639-1 code. */
public record Language(String code, String name) {
    public static final Language AUTO = new Language("auto", "Auto detect");

    @Override
    public String toString() {
        return AUTO.code.equals(code) ? name : name + " (" + code + ")";
    }
}
