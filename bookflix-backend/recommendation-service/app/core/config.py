"""Runtime configuration from environment variables (no secrets have defaults)."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    internal_token: str
    model_dir: Path
    keep_versions: int = 3

    @property
    def token_configured(self) -> bool:
        return bool(self.internal_token)


def load_settings() -> Settings:
    return Settings(
        internal_token=os.environ.get("BOOKFLIX_ML_INTERNAL_TOKEN", "").strip(),
        model_dir=Path(os.environ.get("BOOKFLIX_ML_MODEL_DIR", "./artifacts")).resolve(),
        keep_versions=max(1, int(os.environ.get("BOOKFLIX_ML_KEEP_VERSIONS", "3"))),
    )
