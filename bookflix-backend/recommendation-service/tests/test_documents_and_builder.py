import numpy as np

from app.schemas import CatalogBook, GenreRef, RebuildRequest
from app.training.builder import FORMAT_VERSION, build_model, smoothed_popularity
from app.training.documents import build_document, catalog_frame, clean_text


def test_clean_text_strips_markup_and_entities():
    assert clean_text("<p>Hello&nbsp;<b>world</b> &amp; more</p>") == "Hello world & more"
    assert clean_text(None) == ""


def test_document_weights_title_genres_and_tokens():
    doc = build_document("Dracula", "Bram Stoker", ["Horror", "Horror", "Gothic"], "A count.")
    assert doc.count("Dracula") == 2
    assert doc.count("genre_horror") == 3
    assert doc.count("genre_gothic") == 3
    assert "author_bramstoker" in doc
    assert doc.endswith("A count.")


def test_catalog_frame_dedupes_by_id_and_handles_missing_fields():
    books = [
        CatalogBook(id=2, title="Old", genres=[]),
        CatalogBook(id=1, title="One", description=None, author=None, genres=[GenreRef(id=1, name="X")]),
        CatalogBook(id=2, title="New", genres=[]),
    ]
    df = catalog_frame(books)
    assert df["id"].tolist() == [1, 2]
    assert df.loc[df["id"] == 2, "title"].item() == "New"
    assert df.loc[df["id"] == 1, "description"].item() == ""


def test_build_model_shapes_and_metadata(model):
    assert model.format_version == FORMAT_VERSION
    assert model.book_count == 8
    assert model.matrix.shape[0] == 8
    assert list(model.book_ids) == sorted(model.book_ids)
    norms = np.sqrt(np.asarray(model.matrix.multiply(model.matrix).sum(axis=1)).ravel())
    assert np.allclose(norms, 1.0)
    assert model.model_version.endswith("-abc123de")
    assert model.genre_names[3] == "Science Fiction"
    assert ((model.popularity >= 0) & (model.popularity <= 1)).all()


def test_popularity_is_smoothed_towards_prior():
    pop = smoothed_popularity(np.array([0.0, 0.0]), np.array([1.0, 100.0]), np.array([5.0, 5.0]), np.array([0.0, 0.0]))
    # a single 5-star rating must not beat 100 five-star ratings
    assert pop[1] > pop[0]


def test_build_model_on_full_seed_catalog(seed_request):
    model = build_model(seed_request)
    assert model.book_count >= 60
    assert model.matrix.shape[1] > 500


def test_empty_catalog_rejected():
    import pytest
    from app.training.builder import EmptyCatalogError
    with pytest.raises(EmptyCatalogError):
        build_model(RebuildRequest(catalog_fingerprint="x", books=[]))
