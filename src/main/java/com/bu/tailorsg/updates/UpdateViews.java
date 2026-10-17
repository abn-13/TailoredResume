package com.bu.tailorsg.updates;

import com.bu.tailorsg.master.MasterDiffer.Change;
import com.bu.tailorsg.master.ValidationError;
import com.bu.tailorsg.updates.PendingUpdateService.Preview;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** JSON shapes returned by {@link UpdatesApiController} and the import API. */
public final class UpdateViews {

    private UpdateViews() {}

    public record UpdateSummary(long id, String sourceType, String status, String summary, Instant createdAt,
                                Integer baseVersionNo, Integer appliedVersionNo) {

        public static UpdateSummary of(PendingUpdate u) {
            return new UpdateSummary(u.getId(), u.getSourceType().name(), u.getStatus().name(), u.getSummary(),
                    u.getCreatedAt(), u.getBaseVersionNo(), u.getAppliedVersionNo());
        }
    }

    /** @param preview what applying would do now; null once the update is applied or rejected */
    public record UpdateView(long id, String sourceType, String status, String summary, List<String> notes,
                             Integer baseVersionNo, Integer appliedVersionNo, Instant createdAt, Instant resolvedAt,
                             JsonNode patch, String rawInput, PreviewView preview) {}

    public record PreviewView(boolean canApply, String blockedReason, Integer currentVersionNo, String patchError,
                              List<ValidationError> errors, List<Change> changes, List<String> assignedIds,
                              List<String> newNotes) {

        public static PreviewView of(Preview p) {
            return new PreviewView(p.canApply(), p.blockedReason(), p.baseVersionNo(), p.patchError(), p.errors(),
                    p.changes(), p.assignedIds(), p.newNotes());
        }
    }

    public static UpdateView view(PendingUpdate u, PendingUpdateService service) {
        Preview preview = u.getStatus() == PendingUpdate.Status.PROPOSED ? service.preview(u) : null;
        return new UpdateView(u.getId(), u.getSourceType().name(), u.getStatus().name(), u.getSummary(),
                service.notesOf(u), u.getBaseVersionNo(), u.getAppliedVersionNo(), u.getCreatedAt(), u.getResolvedAt(),
                service.patchOf(u), u.getRawInput(), preview == null ? null : PreviewView.of(preview));
    }
}
