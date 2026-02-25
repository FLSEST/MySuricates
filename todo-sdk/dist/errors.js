"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TimeoutError = exports.NetworkError = exports.UnauthorizedError = exports.ValidationError = exports.NotFoundError = exports.ApiError = void 0;
class ApiError extends Error {
    constructor(status, statusText, body, url) {
        super(`API ${status} ${statusText}: ${url}`);
        this.status = status;
        this.statusText = statusText;
        this.body = body;
        this.url = url;
        this.name = "ApiError";
    }
}
exports.ApiError = ApiError;
class NotFoundError extends ApiError {
    constructor(url, body) {
        super(404, "Not Found", body, url);
        this.name = "NotFoundError";
    }
}
exports.NotFoundError = NotFoundError;
class ValidationError extends ApiError {
    constructor(url, body) {
        super(400, "Bad Request", body, url);
        this.name = "ValidationError";
    }
}
exports.ValidationError = ValidationError;
class UnauthorizedError extends ApiError {
    constructor(url, body) {
        super(401, "Unauthorized", body, url);
        this.name = "UnauthorizedError";
    }
}
exports.UnauthorizedError = UnauthorizedError;
class NetworkError extends Error {
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = "NetworkError";
    }
}
exports.NetworkError = NetworkError;
class TimeoutError extends Error {
    constructor(url, timeoutMs) {
        super(`Request to ${url} timed out after ${timeoutMs}ms`);
        this.url = url;
        this.timeoutMs = timeoutMs;
        this.name = "TimeoutError";
    }
}
exports.TimeoutError = TimeoutError;
//# sourceMappingURL=errors.js.map