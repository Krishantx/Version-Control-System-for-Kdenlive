/**
 * A DOM small enough to assert against and just real enough to run the components.
 *
 * There is no browser here and no jsdom, because the project has no package manager for one.
 * This shim implements only what `lib/dom.js` and the components actually use, and it fails
 * loudly on anything else, so a component that grows a new DOM requirement is caught rather
 * than silently rendering nothing.
 *
 * The point is to test the real components, not a copy of them: if a component asks for
 * something this shim does not have, the test fails and the shim grows.
 */

class Node {
  constructor(tagName) {
    this.tagName = String(tagName).toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.attributes = new Map();
    this.dataset = {};
    this.style = {};
    this.listeners = new Map();
    this._text = null;
  }

  get className() {
    return this.attributes.get('class') || '';
  }

  set className(value) {
    this.attributes.set('class', value);
  }

  get firstChild() {
    return this.children[0] || null;
  }

  set textContent(value) {
    this._text = String(value);
    this.children = [];
  }

  get textContent() {
    if (this._text !== null) return this._text;
    return this.children.map((c) => c.textContent).join('');
  }

  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  removeChild(child) {
    const i = this.children.indexOf(child);
    if (i >= 0) this.children.splice(i, 1);
    child.parentNode = null;
    return child;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(fn);
  }

  /** Fires a listener, standing in for a click. */
  click() {
    const handlers = this.listeners.get('click') || [];
    for (const fn of handlers) fn({ target: this });
  }

  /** Depth-first walk over every element under this one. */
  *walk() {
    yield this;
    for (const child of this.children) {
      if (child instanceof Node) yield* child.walk();
    }
  }

  descendants(tag) {
    const want = String(tag).toUpperCase();
    return [...this.walk()].filter((n) => n !== this && n.tagName === want);
  }

  byClass(name) {
    return [...this.walk()].filter(
      (n) => n !== this && String(n.className).split(/\s+/).includes(name),
    );
  }

  /** The first element whose data attribute matches, for addressing rendered clips. */
  byData(key, value) {
    return [...this.walk()].find(
      (n) => n !== this && String(n.dataset[key]) === String(value),
    );
  }

  /** The rendered position, read back off the inline style the component set. */
  styleOf(prop) {
    return this.style[prop];
  }
}

/** Installs the shim on globalThis and returns a fresh document. */
export function installDom() {
  const document = {
    createElement: (tag) => {
      if (tag === 'body' || tag === 'html') throw new Error('not needed: ' + tag);
      return new Node(tag);
    },
    createTextNode: (text) => {
      const node = new Node('#text');
      node._text = String(text);
      return node;
    },
    getElementById: () => null,
  };
  globalThis.document = document;
  return document;
}

export { Node };
