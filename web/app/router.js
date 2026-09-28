import { DiffPage } from '../pages/DiffPage.js';
import { CommitsPage } from '../pages/CommitsPage.js';

/**
 * The router.
 *
 * Two real paths, and the server serves the same page for both, so a comparison is a link
 * that can be shared and reloaded. There is no hash and no history rewriting beyond what the
 * browser already does.
 */
export function route(root) {
  const path = window.location.pathname.replace(/\/+$/, '') || '/';

  const diffMatch = path.match(/^\/diff\/([^/]+)\/([^/]+)$/);
  if (diffMatch) {
    const [a, b] = [decodeURIComponent(diffMatch[1]), decodeURIComponent(diffMatch[2])];
    const page = new DiffPage(root);
    page.load(a, b);
    return;
  }

  const page = new CommitsPage(root, (a, b) => {
    window.location.pathname = `/diff/${encodeURIComponent(a)}/${encodeURIComponent(b)}`;
  });
  page.load();
}
