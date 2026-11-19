"""Thread-safe holder for the active model with atomic on-disk artifact swaps.

Layout under BOOKFLIX_ML_MODEL_DIR:
    versions/<model_version>/model.joblib
    CURRENT            -> text file containing the active model_version

Rebuild: fit in memory -> dump into versions/.tmp-<uuid>/ -> os.replace() the directory into place ->
write CURRENT.tmp -> os.replace() onto CURRENT -> swap the in-memory reference. Readers take one
reference per request, so they always see a complete model. Only one rebuild runs at a time
(a concurrent request gets RebuildInProgressError -> HTTP 409). Artifacts with an unexpected
format_version are treated as stale and ignored.
"""
from __future__ import annotations

import logging
import os
import shutil
import threading
import uuid
from pathlib import Path

import joblib

from app.evaluation.offline import evaluate
from app.schemas import RebuildRequest
from app.training.builder import FORMAT_VERSION, ModelArtifacts, build_model

log = logging.getLogger(__name__)


class RebuildInProgressError(RuntimeError):
    pass


class ModelStore:
    def __init__(self, model_dir: Path, keep_versions: int = 3) -> None:
        self.model_dir = Path(model_dir)
        self.versions_dir = self.model_dir / "versions"
        self.keep_versions = keep_versions
        self._rebuild_lock = threading.Lock()
        self._current: ModelArtifacts | None = None

    def current(self) -> ModelArtifacts | None:
        return self._current

    def load_latest(self) -> ModelArtifacts | None:
        pointer = self.model_dir / "CURRENT"
        if not pointer.exists():
            return None
        version = pointer.read_text(encoding="utf-8").strip()
        path = self.versions_dir / version / "model.joblib"
        try:
            model = joblib.load(path)
        except Exception:  # corrupt/missing artifact: stay unloaded, Spring will trigger a rebuild
            log.exception("failed to load model artifact %s", path)
            return None
        if not isinstance(model, ModelArtifacts) or model.format_version != FORMAT_VERSION:
            log.warning("ignoring stale model artifact %s (format mismatch)", path)
            return None
        self._current = model
        log.info("loaded model %s (%d books)", model.model_version, model.book_count)
        return model

    def rebuild(self, req: RebuildRequest) -> ModelArtifacts:
        if not self._rebuild_lock.acquire(blocking=False):
            raise RebuildInProgressError("a rebuild is already running")
        try:
            model = build_model(req)
            model.evaluation = evaluate(model, req.interactions)
            self._persist(model)
            self._current = model  # atomic reference swap
            self._prune()
            log.info("rebuilt model %s (%d books)", model.model_version, model.book_count)
            return model
        finally:
            self._rebuild_lock.release()

    def _persist(self, model: ModelArtifacts) -> None:
        self.versions_dir.mkdir(parents=True, exist_ok=True)
        tmp_dir = self.versions_dir / f".tmp-{uuid.uuid4().hex}"
        tmp_dir.mkdir()
        try:
            joblib.dump(model, tmp_dir / "model.joblib", compress=3)
            final_dir = self.versions_dir / model.model_version
            if final_dir.exists():
                shutil.rmtree(final_dir)
            os.replace(tmp_dir, final_dir)
        except Exception:
            shutil.rmtree(tmp_dir, ignore_errors=True)
            raise
        pointer_tmp = self.model_dir / f"CURRENT.tmp-{uuid.uuid4().hex}"
        pointer_tmp.write_text(model.model_version, encoding="utf-8")
        os.replace(pointer_tmp, self.model_dir / "CURRENT")

    def _prune(self) -> None:
        current = self._current.model_version if self._current else None
        versions = sorted((p for p in self.versions_dir.iterdir() if p.is_dir() and not p.name.startswith(".")),
                          key=lambda p: p.name, reverse=True)
        for old in versions[self.keep_versions:]:
            if old.name != current:
                shutil.rmtree(old, ignore_errors=True)
        for tmp in self.versions_dir.glob(".tmp-*"):
            shutil.rmtree(tmp, ignore_errors=True)
