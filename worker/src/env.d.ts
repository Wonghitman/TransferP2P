declare global {
  interface Env {
    ROOM: DurableObjectNamespace;
    PUBLIC_BASE_URL: string;
    WEB_APP_BASE_URL: string;
    ROOM_TTL_SECONDS: string;
  }
}

export {};
