import { useEffect, useRef, useState } from "react";
import { wsUrl } from "@/api/client";

/** An event as the WebSocket sends it, the same shape as in GET /events. */
export interface LiveEvent<D = Record<string, unknown>> {
  type: string;
  at: string;
  atMillis: number;
  player?: { uuid: string; name?: string | null } | null;
  world?: string | null;
  data: D;
}

type Listener = (event: LiveEvent) => void;
export type LinkState = "connecting" | "open" | "closed";

/**
 * One WebSocket for the whole panel. The session cookie signs it in; pages subscribe to topics
 * and the link keeps a count per topic, so it unsubscribes when the last page lets go. It reconnects
 * with a growing pause and subscribes again.
 */
class Link {
  private socket: WebSocket | null = null;
  private listeners = new Map<string, Set<Listener>>();
  private refused = new Map<string, string>();
  private stateListeners = new Set<() => void>();
  private retry = 0;
  private timer: number | undefined;
  state: LinkState = "closed";

  private setState(state: LinkState) {
    this.state = state;
    this.stateListeners.forEach((listener) => listener());
  }

  onState(listener: () => void) {
    this.stateListeners.add(listener);
    return () => void this.stateListeners.delete(listener);
  }

  /** Why WorldAPI refused a topic, such as player.chat while events.chat is off. */
  refusal(topic: string) {
    return this.refused.get(topic);
  }

  subscribe(topic: string, listener: Listener) {
    let set = this.listeners.get(topic);
    if (!set) {
      set = new Set();
      this.listeners.set(topic, set);
      this.send({ op: "subscribe", topics: [topic] });
    }
    set.add(listener);
    this.connect();
    return () => {
      set.delete(listener);
      if (set.size) return;
      this.listeners.delete(topic);
      this.send({ op: "unsubscribe", topics: [topic] });
    };
  }

  /** Closes for good, after signing out; the next subscription opens a fresh link. */
  close() {
    window.clearTimeout(this.timer);
    this.listeners.clear();
    this.refused.clear();
    const socket = this.socket;
    this.socket = null;
    socket?.close();
    this.setState("closed");
  }

  private send(message: unknown) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message));
  }

  private connect() {
    if (this.socket) return;
    window.clearTimeout(this.timer);
    this.setState("connecting");
    const socket = new WebSocket(wsUrl());
    this.socket = socket;
    socket.onopen = () => {
      this.retry = 0;
      this.setState("open");
      const topics = [...this.listeners.keys()];
      if (topics.length) this.send({ op: "subscribe", topics });
    };
    socket.onmessage = (message) => {
      const frame = JSON.parse(String(message.data)) as {
        op: string;
        topic?: string;
        event?: LiveEvent;
        events?: LiveEvent[];
        error?: { code: string; message: string };
      };
      if (frame.op === "event" && frame.topic && frame.event) this.emit(frame.topic, frame.event);
      else if (frame.op === "snapshot" && frame.topic) for (const event of frame.events ?? []) this.emit(frame.topic, event);
      else if (frame.op === "error" && frame.error?.code === "topic_forbidden") {
        for (const topic of this.listeners.keys()) if (frame.error.message.includes(topic)) this.refused.set(topic, frame.error.message);
        this.setState(this.state);
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.setState("closed");
      if (!this.listeners.size) return;
      const wait = Math.min(30_000, 1_000 * 2 ** this.retry++);
      this.timer = window.setTimeout(() => this.connect(), wait);
    };
  }

  private emit(topic: string, event: LiveEvent) {
    this.listeners.get(topic)?.forEach((listener) => listener(event));
  }
}

export const link = new Link();

/** Calls `onEvent` for every event of the topic while the component lives and `enabled` holds. */
export const useTopic = <D = Record<string, unknown>>(topic: string, onEvent: (event: LiveEvent<D>) => void, enabled = true) => {
  const handler = useRef(onEvent);
  handler.current = onEvent;
  useEffect(() => {
    if (!enabled) return;
    return link.subscribe(topic, (event) => handler.current(event as LiveEvent<D>));
  }, [topic, enabled]);
};

/** The link's state and a topic's refusal, for a “Live” badge. */
export const useLink = (topic?: string) => {
  const [, render] = useState(0);
  useEffect(() => link.onState(() => render((n) => n + 1)), []);
  return { state: link.state, refused: topic ? link.refusal(topic) : undefined };
};
