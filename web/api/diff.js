/**
 * The one place the frontend talks to the server.
 *
 * It knows two URLs and nothing about repositories, commits on disk, or how a comparison is
 * assembled. What it gets back is the same JSON the command line's `--json` produces, because
 * it is the same object, serialized by the same code.
 */

/** Raised when the server answered, but with a refusal. */
export class ApiError extends Error {
  constructor(status, body) {
    const detail = body && body.error ? body.error : {};
    super(detail.detail || detail.title || `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.title = detail.title || 'Request failed';
    this.detail = detail.detail || '';
  }
}

async function request(url) {
  let response;
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (cause) {
    // fetch only rejects for a transport problem, so this is "the server is not there"
    // rather than anything the server said.
    throw new ApiError(0, {
      error: { title: 'Cannot reach the server', detail: String(cause && cause.message) },
    });
  }
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (!response.ok) throw new ApiError(response.status, body);
  return body;
}

/** The comparison between two commits, exactly as the backend built it. */
export function fetchDiff(commitA, commitB) {
  return request(`/api/diff/${encodeURIComponent(commitA)}/${encodeURIComponent(commitB)}`);
}

/** The history, so two commits can be chosen rather than typed. */
export function fetchCommits() {
  return request('/api/commits');
}
