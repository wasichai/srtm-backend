#!/usr/bin/env python3
"""Apply model/model.json to a wasichai Core (srtm-backend).

Reads a JSON metadata model (objects, fields, relationships, enums) and
creates it in Core via the REST API, in file order (so relationship targets
already exist), or tears it down with --drop in reverse order.

Adapted from wasichai's examples/gis-sample/perene/apply.py, without geometry:
srtm installs no gis. Stdlib only. See README.md.
"""
import argparse
import json
import os
import re
import sys

from core_client import Client, CoreError

# Keyword list copied verbatim from
# wasichai-core/src/main/kotlin/wasichai/core/platform/SqlIdentifier.kt (SQL_KEYWORDS).
SQL_KEYWORDS = frozenset({
    "all", "alter", "and", "any", "array", "as", "asc", "begin", "between",
    "by", "case", "cast", "check", "column", "commit", "constraint",
    "create", "cross", "current_date", "current_time", "current_timestamp",
    "current_user", "default", "delete", "desc", "distinct", "do", "drop",
    "else", "end", "except", "exists", "false", "fetch", "for", "foreign",
    "from", "full", "grant", "group", "having", "in", "index", "inner",
    "insert", "intersect", "into", "is", "join", "key", "left", "like",
    "limit", "not", "null", "offset", "on", "or", "order", "outer",
    "primary", "references", "returning", "right", "rollback", "select",
    "session_user", "set", "some", "table", "then", "to", "true", "union",
    "unique", "update", "user", "using", "values", "view", "when", "where",
    "with",
})

# Field names Core reserves: system columns + query-param names used on list endpoints.
RESERVED_FIELD_NAMES = frozenset({
    "id", "organization_id", "created_at", "updated_at", "created_by",
    "updated_by", "workflow_state", "version",
    "page", "size", "sort", "dir", "q", "bbox", "geometry", "limit",
})

VALID_NAME = re.compile(r"^[a-z][a-z0-9_]{0,48}$")

# Mirror Core's ENUM option regex (metadata/MetadataService.kt):
# ^[\p{L}0-9 _.-]{1,64}$ -- Unicode letters, ASCII digits, space, `_`, `.`, `-`.
# Checked per character: Python's `\w` tricks also match non-letter "word" characters.
ENUM_OPTION_EXTRA_CHARS = re.compile(r"[0-9 _.\-]")


def enum_option_valid(opt):
    if not isinstance(opt, str) or not (1 <= len(opt) <= 64):
        return False
    return all(c.isalpha() or ENUM_OPTION_EXTRA_CHARS.fullmatch(c) for c in opt)


MAX_OBJECT_NAME = 39
MAX_FIELD_NAME = 49
MAX_RELATIONSHIP_NAME = 35

# core's scalar types. GEOMETRY belongs to wasichai-gis, which srtm does not install
FIELD_TYPES = frozenset({
    "TEXT", "LONG_TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
    "ENUM", "EMAIL", "URL", "UUID",
})


# ---------------------------------------------------------------------------
# validation
# ---------------------------------------------------------------------------

def _check_identifier(name, kind, maxlen, label):
    """Mirror SqlIdentifier.requireValidName: shape, length, not a keyword."""
    errors = []
    if not isinstance(name, str) or not name:
        return [f"{label}: {kind} name is missing"]
    if len(name) > maxlen:
        errors.append(f"{label}: {kind} name '{name}' exceeds {maxlen} characters")
    if not VALID_NAME.fullmatch(name):
        errors.append(
            f"{label}: {kind} name '{name}' must match ^[a-z][a-z0-9_]{{0,48}}$"
        )
    if name in SQL_KEYWORDS:
        errors.append(f"{label}: {kind} name '{name}' is a reserved SQL keyword")
    return errors


def validate(model):
    """Pure structural + Core-mirroring validation. Returns a list of error strings."""
    errors = []

    enums = model.get("enums")
    if not isinstance(enums, dict):
        errors.append("enums must be an object")
        enums = {}
    else:
        for ename, opts in enums.items():
            if not isinstance(opts, list) or not opts or not all(isinstance(o, str) for o in opts):
                errors.append(f"enum {ename}: must be a non-empty list of strings")
                continue
            seen_opts = set()
            for opt in opts:
                if not enum_option_valid(opt):
                    errors.append(f"enum {ename}: option '{opt}' has invalid characters or length")
                if opt in seen_opts:
                    errors.append(f"enum {ename}: duplicate option '{opt}'")
                seen_opts.add(opt)

    objects = model.get("objects")
    if not isinstance(objects, list) or not objects:
        errors.append("objects must be a non-empty list")
        objects = []

    relationships = model.get("relationships")
    if not isinstance(relationships, list):
        errors.append("relationships must be a list")
        relationships = []

    object_order = []
    seen_objects = set()
    fields_by_object = {}

    for obj in objects:
        oname = obj.get("name", "")
        label = f"object {oname}"
        if oname in seen_objects:
            errors.append(f"{label}: duplicate object name")
        seen_objects.add(oname)
        object_order.append(oname)
        errors.extend(_check_identifier(oname, "object", MAX_OBJECT_NAME, label))

        field_names = set()
        for field in obj.get("fields", []):
            fname = field.get("name", "")
            ftype = field.get("type")
            flabel = f"{label} field {fname}"
            if fname in field_names:
                errors.append(f"{flabel}: duplicate field name")
            field_names.add(fname)

            errors.extend(_check_identifier(fname, "field", MAX_FIELD_NAME, flabel))
            if fname in RESERVED_FIELD_NAMES:
                errors.append(f"{flabel}: field name is reserved by Core")

            if ftype == "RELATION":
                errors.append(f"{flabel}: type RELATION is not allowed here (relationships create it)")
            elif ftype not in FIELD_TYPES:
                errors.append(f"{flabel}: unknown type '{ftype}'")

            if ftype == "ENUM":
                enum_name = field.get("enum")
                if not enum_name or enum_name not in enums:
                    errors.append(f"{flabel}: enum '{enum_name}' is not defined in enums")

        fields_by_object[oname] = field_names

    rel_names = set()
    seen_source_field_names = set()
    for rel in relationships:
        rname = rel.get("name", "")
        label = f"relationship {rname}"
        if rname in rel_names:
            errors.append(f"{label}: duplicate relationship name")
        rel_names.add(rname)
        errors.extend(_check_identifier(rname, "relationship", MAX_RELATIONSHIP_NAME, label))

        source = rel.get("source")
        target = rel.get("target")
        if source not in seen_objects:
            errors.append(f"{label}: source '{source}' is not a known object")
        if target not in seen_objects:
            errors.append(f"{label}: target '{target}' is not a known object")

        if source == target:
            errors.append(f"{label}: self-references are not allowed")
        elif source in object_order and target in object_order:
            if object_order.index(target) > object_order.index(source):
                errors.append(
                    f"{label}: target '{target}' must appear before source '{source}' in objects"
                )

        field_name = rel.get("fieldName")
        errors.extend(_check_identifier(field_name, "field", MAX_FIELD_NAME, label))
        if field_name in RESERVED_FIELD_NAMES:
            errors.append(f"{label}: fieldName '{field_name}' is reserved by Core")
        if source in fields_by_object and field_name in fields_by_object[source]:
            errors.append(f"{label}: fieldName '{field_name}' collides with an existing field of {source}")

        source_field_key = (source, field_name)
        if source_field_key in seen_source_field_names:
            errors.append(
                f"{label}: fieldName '{field_name}' duplicates another relationship from {source}"
            )
        seen_source_field_names.add(source_field_key)

    return errors


# ---------------------------------------------------------------------------
# payload builders (pure)
# ---------------------------------------------------------------------------

def object_payload(model, obj):
    fields = []
    for f in obj["fields"]:
        field = {
            "name": f["name"],
            "label": f["label"],
            "type": f["type"],
            "required": f.get("required", False),
            "unique": f.get("unique", False),
        }
        if "description" in f:
            field["description"] = f["description"]
        if f["type"] == "ENUM":
            field["enumOptions"] = model["enums"][f["enum"]]
        fields.append(field)
    return {
        "name": obj["name"],
        "label": obj["label"],
        "pluralLabel": obj["pluralLabel"],
        "description": obj.get("description", ""),
        "fields": fields,
    }


def relationship_payload(rel):
    return {
        "name": rel["name"],
        "label": rel["label"],
        "inverseLabel": rel["inverseLabel"],
        "type": "MANY_TO_ONE",
        "source": rel["source"],
        "target": rel["target"],
        "fieldName": rel["fieldName"],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _existing_names(data):
    if isinstance(data, dict):
        data = data.get("items", data.get("content", []))
    return {o["name"] for o in (data or [])}


def _fatal(method, path, err):
    print(f"error {method} {path} -> {err.status}\n{err.body}", file=sys.stderr)


def _apply_required_put(client, rel):
    """Returns True to keep going, False if this was fatal (caller must stop and exit 1).

    Unlike object/relationship POSTs (which tolerate 409 "ya existe"), this PUT tolerates
    nothing: every non-2xx response is fatal.
    """
    if not rel.get("required"):
        return True
    path = f"/api/metadata/objects/{rel['source']}/fields/{rel['fieldName']}"
    try:
        client.put(path, {"required": True})
    except CoreError as e:
        _fatal("PUT", path, e)
        return False
    return True


def do_apply(client, model):
    created = 0
    skipped = 0

    existing = _existing_names(client.get("/api/objects"))
    for obj in model["objects"]:
        name = obj["name"]
        if name in existing:
            print(f"skip   object {name} (exists)")
            skipped += 1
            continue
        try:
            status, _ = client.post("/api/objects", object_payload(model, obj))
            print(f"create object {name} ({status})")
            created += 1
        except CoreError as e:
            if e.status == 409:
                print(f"skip   object {name} (exists)")
                skipped += 1
            else:
                _fatal("POST", "/api/objects", e)
                return 1

    for rel in model["relationships"]:
        name = rel["name"]
        try:
            status, _ = client.post("/api/relationships", relationship_payload(rel))
            print(f"create relationship {name} ({status})")
            created += 1
        except CoreError as e:
            if e.status == 409:
                print(f"skip   relationship {name} (exists)")
                skipped += 1
            else:
                _fatal("POST", "/api/relationships", e)
                return 1
        if not _apply_required_put(client, rel):
            return 1

    print(f"done: {created} created, {skipped} skipped")
    return 0


def _delete_one(client, label, path):
    """Delete one entity; print the result. Returns 'deleted', 'skipped', or None (fatal, already reported)."""
    try:
        status, _ = client.delete(path)
        print(f"delete {label} ({status})")
        return "deleted"
    except CoreError as e:
        if e.status == 404:
            print(f"skip   {label} (not found)")
            return "skipped"
        _fatal("DELETE", path, e)
        return None


def do_drop(client, model):
    deleted = 0
    skipped = 0

    to_delete = [
        (f"relationship {rel['name']}", f"/api/relationships/{rel['name']}")
        for rel in reversed(model["relationships"])
    ] + [
        (f"object {obj['name']}", f"/api/objects/{obj['name']}")
        for obj in reversed(model["objects"])
    ]
    for label, path in to_delete:
        result = _delete_one(client, label, path)
        if result is None:
            return 1
        deleted += result == "deleted"
        skipped += result == "skipped"

    print(f"done: {deleted} deleted, {skipped} skipped")
    return 0


def _print_dry_run(model):
    for obj in model["objects"]:
        print("# POST /api/objects")
        print(json.dumps(object_payload(model, obj), indent=2, ensure_ascii=False))
    for rel in model["relationships"]:
        print("# POST /api/relationships")
        print(json.dumps(relationship_payload(rel), indent=2, ensure_ascii=False))


def _print_drop_dry_run(model):
    for rel in reversed(model["relationships"]):
        print(f"# DELETE /api/relationships/{rel['name']}")
    for obj in reversed(model["objects"]):
        print(f"# DELETE /api/objects/{obj['name']}")


def default_model_path():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "model.json")


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Apply srtm's model.json to a wasichai Core.")
    p.add_argument("--model", default=default_model_path())
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--drop", action="store_true")
    p.add_argument("--validate-only", action="store_true")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    with open(args.model, encoding="utf-8") as f:
        model = json.load(f)

    errors = validate(model)
    if errors:
        for e in errors:
            print(f"model: {args.model}: {e}")
        return 2
    if args.validate_only:
        return 0

    if args.dry_run:
        if args.drop:
            _print_drop_dry_run(model)
        else:
            _print_dry_run(model)
        return 0

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
    except CoreError as e:
        _fatal("POST", "/api/auth/login", e)
        return 1

    if args.drop:
        return do_drop(client, model)
    return do_apply(client, model)


if __name__ == "__main__":
    sys.exit(main())
