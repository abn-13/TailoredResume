package com.bu.tailorsg.documents;

/** An uploaded file could not be turned into text. The message is written to be shown to the user. */
public class UnreadableDocumentException extends RuntimeException {

    public UnreadableDocumentException(String message) {
        super(message);
    }
}
