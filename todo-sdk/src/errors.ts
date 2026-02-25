export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly statusText: string,
    public readonly body: unknown,
    public readonly url: string,
  ) {
    super(`API ${status} ${statusText}: ${url}`);
    this.name = "ApiError";
  }
}

export class NotFoundError extends ApiError {
  constructor(url: string, body: unknown) {
    super(404, "Not Found", body, url);
    this.name = "NotFoundError";
  }
}

export class ValidationError extends ApiError {
  constructor(url: string, body: unknown) {
    super(400, "Bad Request", body, url);
    this.name = "ValidationError";
  }
}

export class UnauthorizedError extends ApiError {
  constructor(url: string, body: unknown) {
    super(401, "Unauthorized", body, url);
    this.name = "UnauthorizedError";
  }
}

export class NetworkError extends Error {
  constructor(
    message: string,
    public readonly cause?: Error,
  ) {
    super(message);
    this.name = "NetworkError";
  }
}

export class TimeoutError extends Error {
  constructor(
    public readonly url: string,
    public readonly timeoutMs: number,
  ) {
    super(`Request to ${url} timed out after ${timeoutMs}ms`);
    this.name = "TimeoutError";
  }
}
