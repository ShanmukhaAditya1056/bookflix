package com.bookflix.recommendations;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.bookflix.common.ApiException;
import com.bookflix.common.ErrorCode;
import com.bookflix.recommendations.MlClient.MlUnavailableException;
import com.bookflix.recommendations.MlDtos.HealthResponse;
import com.bookflix.recommendations.MlDtos.RebuildRequest;
import com.bookflix.recommendations.MlDtos.RebuildResponse;

/**
 * Keeps the ML model in sync with the catalog:
 * <ul>
 *   <li>catalog mutations mark the model stale; a scheduled check rebuilds it (debounced);</li>
 *   <li>the check also rebuilds when the ML service lost its model or serves a different fingerprint;</li>
 *   <li>a periodic rebuild refreshes popularity signals;</li>
 *   <li>admins can force a synchronous rebuild. Only one rebuild runs at a time.</li>
 * </ul>
 */
@Service
public class ModelRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ModelRebuildService.class);

    private final MlClient ml;
    private final CatalogSnapshotService snapshots;
    private final AtomicBoolean stale = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastFingerprint = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public ModelRebuildService(MlClient ml, CatalogSnapshotService snapshots) {
        this.ml = ml;
        this.snapshots = snapshots;
    }

    public record RebuildResult(String status, String modelVersion, int bookCount, String builtAt,
                                Map<String, Object> evaluation) {
    }

    public record ModelStatus(boolean enabled, boolean available, String modelVersion, String builtAt,
                              Integer bookCount, boolean stale, String lastError) {
    }

    public void markCatalogChanged() {
        stale.set(true);
    }

    @Scheduled(initialDelayString = "${bookflix.ml.rebuild-check-initial-delay:PT15S}",
            fixedDelayString = "${bookflix.ml.rebuild-check-interval}")
    public void scheduledCheck() {
        if (!ml.usable()) {
            return;
        }
        try {
            boolean needed = stale.get();
            if (!needed) {
                HealthResponse health = ml.health();
                needed = !health.modelLoaded() || !Objects.equals(health.catalogFingerprint(), lastFingerprint.get());
            }
            if (needed) {
                rebuildInternal();
            }
        } catch (MlUnavailableException e) {
            lastError.set(e.getMessage());
            log.debug("Scheduled model check skipped: {}", e.getMessage());
        } catch (ApiException e) {
            log.debug("Scheduled model check skipped: {}", e.getMessage());
        }
    }

    @Scheduled(initialDelayString = "${bookflix.ml.periodic-rebuild-interval}",
            fixedDelayString = "${bookflix.ml.periodic-rebuild-interval}")
    public void periodicRefresh() {
        markCatalogChanged();
    }

    /** Synchronous admin-triggered rebuild. */
    public RebuildResult rebuildNow() {
        if (!ml.usable()) {
            throw new ApiException(ErrorCode.ML_UNAVAILABLE, "Recommendation service is disabled or not configured");
        }
        try {
            return rebuildInternal();
        } catch (MlUnavailableException e) {
            lastError.set(e.getMessage());
            if (e.status() != null && e.status().value() == HttpStatus.CONFLICT.value()) {
                throw new ApiException(ErrorCode.REBUILD_IN_PROGRESS, "A model rebuild is already in progress");
            }
            throw new ApiException(ErrorCode.ML_UNAVAILABLE, "Recommendation service is unavailable");
        }
    }

    public ModelStatus status() {
        if (!ml.usable()) {
            return new ModelStatus(false, false, null, null, null, stale.get(), "disabled or not configured");
        }
        try {
            HealthResponse h = ml.health();
            return new ModelStatus(true, h.modelLoaded(), h.modelVersion(), h.builtAt(), h.bookCount(),
                    stale.get() || !Objects.equals(h.catalogFingerprint(), lastFingerprint.get()), lastError.get());
        } catch (MlUnavailableException e) {
            return new ModelStatus(true, false, null, null, null, stale.get(), e.getMessage());
        }
    }

    private RebuildResult rebuildInternal() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(ErrorCode.REBUILD_IN_PROGRESS, "A model rebuild is already in progress");
        }
        try {
            // Clear first: a catalog change that lands during the rebuild re-marks it stale.
            stale.set(false);
            RebuildRequest snapshot = snapshots.snapshot();
            RebuildResponse resp = ml.rebuild(snapshot);
            lastFingerprint.set(resp.catalogFingerprint());
            lastError.set(null);
            log.info("Recommendation model rebuilt: version={} books={}", resp.modelVersion(), resp.bookCount());
            return new RebuildResult("REBUILT", resp.modelVersion(), resp.bookCount(), resp.builtAt(),
                    resp.evaluation());
        } catch (RuntimeException e) {
            stale.set(true);
            throw e;
        } finally {
            running.set(false);
        }
    }
}
