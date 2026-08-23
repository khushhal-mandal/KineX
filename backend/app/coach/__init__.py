"""Retrieval and prompt assembly, shared by the batch job and the chat endpoint.

Its own package rather than living in `app/api/coach.py` because the nightly job needs
the same fact queries and must not import a router to get them. Neither half knows which
model it is feeding — that is `app/llm/`.
"""
