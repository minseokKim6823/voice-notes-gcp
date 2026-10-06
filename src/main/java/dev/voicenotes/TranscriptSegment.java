package dev.voicenotes;

public record TranscriptSegment(int sequence, String speaker, String text, long startMs, long endMs) {}
