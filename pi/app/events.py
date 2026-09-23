"""Fan-out to every connected WebSocket.

Each client gets its own bounded queue. A client that can't keep up loses frames rather
than blocking the pour — and since every event carries a whole object, a dropped frame
is self-correcting: the next one replaces the state wholesale.
"""

from __future__ import annotations

import asyncio
import logging
import time

from .models import Event

log = logging.getLogger("bartender.events")

QUEUE_SIZE = 32
HEARTBEAT_SECONDS = 5.0


def now_ms() -> int:
    return int(time.time() * 1000)


class Subscriber:
    def __init__(self) -> None:
        self.queue: asyncio.Queue[Event] = asyncio.Queue(maxsize=QUEUE_SIZE)
        self.seq = 0

    def offer(self, type_: str, data: dict) -> None:
        self.seq += 1
        event = Event(type=type_, seq=self.seq, ts=now_ms(), data=data)
        try:
            self.queue.put_nowait(event)
        except asyncio.QueueFull:
            log.debug("subscriber is behind, dropping a %s frame", type_)


class EventBus:
    def __init__(self) -> None:
        self._subscribers: set[Subscriber] = set()

    def subscribe(self) -> Subscriber:
        subscriber = Subscriber()
        self._subscribers.add(subscriber)
        return subscriber

    def unsubscribe(self, subscriber: Subscriber) -> None:
        self._subscribers.discard(subscriber)

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

    def publish(self, type_: str, data: dict) -> None:
        for subscriber in tuple(self._subscribers):
            subscriber.offer(type_, data)
