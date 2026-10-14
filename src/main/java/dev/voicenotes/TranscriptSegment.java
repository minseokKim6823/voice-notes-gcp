package dev.voicenotes;

public record TranscriptSegment(int sequence, String speaker, String text, long startMs, long endMs,
                                String speakerName) {
    public TranscriptSegment(int sequence, String speaker, String text, long startMs, long endMs) {
        this(sequence, speaker, text, startMs, endMs, "");
    }

    public TranscriptSegment {
        speakerName = speakerName == null ? "" : speakerName;
    }

    public String displayName() {
        return speakerName.isBlank() ? ("미확인".equals(speaker) ? "미확인 화자" : "화자 " + speaker) : speakerName;
    }

    public TranscriptSegment withSpeaker(String id, String name) {
        return new TranscriptSegment(sequence, id, text, startMs, endMs, name);
    }
}
