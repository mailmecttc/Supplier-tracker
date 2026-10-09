export type Handler = (el: HTMLElement, ev: Event) => void;

export interface Actions {
  click?: Record<string, Handler>;
  change?: Record<string, Handler>;
  input?: Record<string, Handler>;
}
