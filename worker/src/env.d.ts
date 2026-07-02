export interface Env {
  ROOM: DurableObjectNamespace;
  PUBLIC_BASE_URL: string;
  ROOM_TTL_SECONDS: string;
  TURN_KEY_ID?: string;
  TURN_KEY_API_TOKEN?: string;
}

export {};
