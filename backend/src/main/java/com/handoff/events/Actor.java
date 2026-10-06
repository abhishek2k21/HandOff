package com.handoff.events;

public record Actor(
    ActorKind kind,
    String id,
    String name
) {}
