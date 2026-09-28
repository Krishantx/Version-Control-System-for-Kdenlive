/**
 * The only DOM helper in the project.
 *
 * Building elements by assigning `innerHTML` is how a viewer built from a JSON response ends
 * up running code that came from a file name, so nothing here parses a string into markup.
 * Text goes in as text, always.
 */

export function el(tag, attributes = {}, children = []) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === null || value === undefined || value === false) continue;
    if (name === 'class') {
      node.className = value;
    } else if (name === 'text') {
      node.textContent = String(value);
    } else if (name === 'style' && typeof value === 'object') {
      for (const [prop, v] of Object.entries(value)) node.style[prop] = v;
    } else if (name.startsWith('on') && typeof value === 'function') {
      node.addEventListener(name.slice(2).toLowerCase(), value);
    } else if (name === 'data' && typeof value === 'object') {
      for (const [k, v] of Object.entries(value)) node.dataset[k] = v;
    } else {
      node.setAttribute(name, value === true ? '' : String(value));
    }
  }
  for (const child of [].concat(children)) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

/** Replaces the contents of a node in one step. */
export function render(node, children) {
  clear(node);
  for (const child of [].concat(children)) {
    if (child) node.appendChild(child);
  }
  return node;
}
