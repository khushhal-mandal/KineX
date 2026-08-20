"""Structured logging: one JSON object per line, on stdout.

Stdlib `logging` with a formatter, no third-party logging dependency. Uvicorn's own
loggers are re-pointed at the root handler so that access lines, error lines and
application lines are all the same shape — a container whose stdout is half JSON and
half uvicorn's default format cannot be parsed by anything downstream.
"""

import datetime
import json
import logging
import sys

# LogRecord's own attributes. Anything outside this set arrived via `extra=` and is a
# field the caller wants in the output.
#
# `color_message` is not a LogRecord attribute: uvicorn attaches it to its own records as
# a duplicate of the message carrying ANSI escapes. It is dropped here because it would
# otherwise put terminal control codes into every JSON line uvicorn emits.
_RECORD_ATTRS = frozenset(
    {
        "args", "asctime", "color_message", "created", "exc_info", "exc_text", "filename",
        "funcName", "levelname", "levelno", "lineno", "module", "msecs", "message", "msg",
        "name", "pathname", "process", "processName", "relativeCreated", "stack_info",
        "taskName", "thread", "threadName",
    }
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": datetime.datetime.fromtimestamp(
                record.created, datetime.UTC
            ).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "event": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RECORD_ATTRS:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())

    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())

    # Uvicorn installs its own handlers before it imports the app. Strip them and let
    # its records propagate to the root handler above, or every line is logged twice.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True
