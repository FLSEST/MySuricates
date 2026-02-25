export declare class ApiError extends Error {
    readonly status: number;
    readonly statusText: string;
    readonly body: unknown;
    readonly url: string;
    constructor(status: number, statusText: string, body: unknown, url: string);
}
export declare class NotFoundError extends ApiError {
    constructor(url: string, body: unknown);
}
export declare class ValidationError extends ApiError {
    constructor(url: string, body: unknown);
}
export declare class UnauthorizedError extends ApiError {
    constructor(url: string, body: unknown);
}
export declare class NetworkError extends Error {
    readonly cause?: Error | undefined;
    constructor(message: string, cause?: Error | undefined);
}
export declare class TimeoutError extends Error {
    readonly url: string;
    readonly timeoutMs: number;
    constructor(url: string, timeoutMs: number);
}
