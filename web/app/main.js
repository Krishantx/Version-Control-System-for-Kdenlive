/**
 * Where the page starts.
 *
 * The server serves this same document for every path that is not a file, so routing happens
 * here rather than on the server, and `/diff/a/b` survives a reload.
 */
import { route } from './router.js';

const root = document.getElementById('root');
route(root);
