# ClojureScript Circular Dependencies

This is a demo to illustrate a simple circular dependency problem in
ClojureScript.  We explain why such a problem is resolved in Advanced
Optimization mode (via namespace-joining and function-hoisting) and why it
is not resolved in Simple Optimization mode (preserves namespaces).

## The short version

In the example below, we have two files in the `example` namespace.  Each of
them reference each other's `init` functions.

```clj
(ns example.foo
  #_(:require [example.bar]))

(defn init []
  (println "Hello from Foo"))

(def init-bar example.bar.init)

```

```clj
(ns example.bar
  #_(:require [example.foo]))

(defn init []
  (println "Hello from Bar"))

(def init-foo example.foo.init)
```

If each of these files used `:require` directives to reference each other, the
compiler would never succeed due to circular dependency errors.  But the code
_will_ compile successfully if we omit them (using the "ignore-next-form"
reader macro `#_`).

The resulting compiled JS will only run without errors in `:optimizations
:advanced` mode.  Here's why:

__Simple optimizations__ mode preserves namespaces as JS objects, so you can see
the point where it would fail due to an unresolved symbol (commented below).

```javascript
// simple optimization output

example = {};

example.foo = {};
example.foo.init = function() {
    console.log("Hello from Foo");
};
example.foo.init_bar = example.bar.init;  //  <--- ERROR (not defined yet)

example.bar = {};
example.bar.init = function() {
    console.log("Hello from Bar");
};
example.bar.init_foo = example.foo.init;  // <--- (resolves, even though we didn't use :require)
```

__Advanced optimizations__ mode munges all namespaced symbols into the same scope, flattening
the namespaces together. (The names are preserved below for readability).

```javascript
// advanced optimization output

function foo_init() {
    console.log("Hello from Foo");
};
foo_init_bar = bar_init;  // <--- succeeds, because "bar_init" is hoisted

function bar_init() {
    console.log("Hello from Bar");
};
bar_init_foo = foo_init;  // <--- succeeds
```

Notice that all functions in Advanced mode are __hoisted__.  Functions are only
hoisted if they are of the form `function name() { body }` , not the form
`name = function() { body }` form.  So functions inside namespaces (js
objects) cannot be referenced before they are defined.

#### A note on Hoisting of Exported symbols

After looking at the compilation strategies of Simple vs Advanced, it might
seem that using `^:export` on a function would preserve the namespace
hierarchy, thus preventing hoisting.  This is not true.

Exported symbols are still flattened and munged, but they are written to JS
variables using their fully qualified namespace:

```javascript
exportToJS("example.foo.init", foo_init);
exportToJS("example.bar.init", bar_init);
```


### The short solution

If you are referencing a function in another namespace, and you cannot
`:require` it without causing a circular dependency, and you don't
want to use Advanced optimizations mode, you can do this:

```clj
(def init-bar #(example.bar.init))
```

or

```clj
(defn init-bar [] (example.bar.init))
```

The idea is that you cannot evaluate the external symbol until is defined, so
wrapping it in a function will prevent it from being evaluated with the
namespace, as long as it is invoked after the external symbol is defined.

You can also use the [`delay`](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/delay) macro.


```clj
(defn init-bar (delay example.bar.init))
```

But to invoke the function, you must use the deref form `@`.  The delay body is
only evaluated the first time it is deref'd.

```clj
; invoke it somewhere by deref'ing it
(@init-bar)
```

## A real example

This project includes a Single Page App illustrating a real circular dependency
problem.

### Running

Run the following to build the code:

```
lein cljsbuild auto
```

After compiling, open `simple.html` to see that it does nothing (see console
for error), then open `advanced.html` to see that it cycles between the Foo and
Bar pages.

### Code

`core.cljs` contains a `pages` map which references all the page initialization
functions.  It also has a `set-page` function for setting the current page.

```clj
(ns circle.core
  #_(:require
      [circle.foo]
      [circle.bar]))

(def pages {:foo {:init circle.foo.init}
            :bar {:init circle.bar.init}})

(defn set-page [page-key]
  (let [init-fn (get-in pages [page-key :init])]
    (init-fn)))

(.addEventListener js/window "load" #(set-page :foo))
```

(Note: we are using the "ignore-next-form" reader macro `#_` to ignore the
requiring of `circle.foo` and `circle.bar`. This cuts the circular dependency,
allowing our program to compile.)

`foo.cljs` and `bar.cljs` are pages that display their page name, then after 2
seconds navigate to the other page (to mimick user navigation between them).

```clj
(ns circle.foo
  (:require
    [circle.core :refer [set-page]]))

(defn init []
  (aset js/document.body "innerHTML" "On page Foo")
  (js/window.setTimeout #(set-page :bar) 2000))
```

```clj
(ns circle.bar
  (:require
    [circle.core :refer [set-page]]))

(defn init []
  (aset js/document.body "innerHTML" "On page Bar")
  (js/window.setTimeout #(set-page :foo) 2000))
```

### The circular dependency

This is an interesting problem.  The very nature of a "pages map" seems to
require a cyclic dependency graph, because every page should be able to
navigate to another page.  It's very "web"-like, of course.

```
                (We cut the circular dependency
                  here for compiling)
                        |
                        |
                        v       |-----------------------|
 PAGES MAP   --------------------> PAGE BUILD FUNCTIONS |
                (references)    |                       |
     ^                          |          |            |
     |                          |          |            |
     | (calls)                  |          | (setup)    |
     |                          |          v            |
                                |                       |
SET CURRENT  <-------------------- PAGE EVENT FUNCTIONS |
   PAGE           (calls)       |-----------------------|

```

NOTE: This problem wouldn't exist in a multi-page app, where only URLs would be used for navigation.
Our single page app tries to encapsulates the responsibility of navigation, which makes
visible the layer at which these pages are interacting as a circular dependency.

### What :require really does

ClojureScript does not allow `:require` to create a circular dependency,
because it uses `:require` to determine the order at which the namespaces are
evaluated (i.e. the sequence of evaluated statements in the compiled JS file).

Perhaps unintuiviely, a `:require` is _not_ necessary for using symbols from
other namespaces.  In fact, using symbols without a `:require` can be safely
done if:

  1. The external symbol is fully qualified with its given namespace, and...
  2. The external symbol is inside an expression that is not
     evaluated immediately (e.g. the body of a function)

### Real Optimizations Modes Output

__Simple Optimizations Output__

```javascript
var circle = {core:{}};
circle.core.pages = new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null, "foo", "foo", 1014005816), new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null, "init", "init", 1017141378), circle.foo.init], null), new cljs.core.Keyword(null, "bar", "bar", 1014001541), new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null, "init", "init", 1017141378), circle.bar.init], null)], null);
circle.core.set_page = function(a) {
  return cljs.core.get_in.call(null, circle.core.pages, new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [a, new cljs.core.Keyword(null, "init", "init", 1017141378)], null)).call(null);
};
window.addEventListener("load", function() {
  return circle.core.set_page.call(null, new cljs.core.Keyword(null, "foo", "foo", 1014005816));
});
circle.foo = {};
circle.foo.init = function() {
  document.body.innerHTML = "On page Foo";
  return window.setTimeout(function() {
    return circle.core.set_page.call(null, new cljs.core.Keyword(null, "bar", "bar", 1014001541));
  }, 2E3);
};
circle.bar = {};
circle.bar.init = function() {
  document.body.innerHTML = "On page Bar";
  return window.setTimeout(function() {
    return circle.core.set_page.call(null, new cljs.core.Keyword(null, "foo", "foo", 1014005816));
  }, 2E3);
};
```

__Advanced Optimizations Output__

```javascript
var Xa = new X(null, "dup", "dup"), bb = new X(null, "default", "default"), ad = new X(null, "foo", "foo"), bd = new X(null, "init", "init"), cd = new X(null, "bar", "bar"), Ua = new X(null, "flush-on-newline", "flush-on-newline"), Ya = new X(null, "print-length", "print-length"), t = new X(null, "else", "else"), Va = new X(null, "readably", "readably"), Wa = new X(null, "meta", "meta");
var fd = new Ta(null, 2, [ad, new Ta(null, 1, [bd, dd], null), cd, new Ta(null, 1, [bd, ed], null)], null);
function gd(a) {
  a = $b.a(fd, new Y(null, 2, 5, nc, [a, bd], null));
  return a.ha ? "" : a.call(null);
}
window.addEventListener("load", function() {
  return gd(ad);
});
function dd() {
  document.body.innerHTML = "On page Foo";
  return window.setTimeout(function() {
    return gd(cd);
  }, 2E3);
}
;function ed() {
  document.body.innerHTML = "On page Bar";
  return window.setTimeout(function() {
    return gd(ad);
  }, 2E3);
}
// code below is present only if the init functions are exported:
ca("circle.foo.init", fd);
ca("circle.bar.init", gd);
```


### The solution

In `core.cljs` wrap the init functions in functions:

```clj
(def pages {:foo {:init #(circle.foo.init)}
            :bar {:init #(circle.bar.init)}})
```

This defers the evaluation of a symbol until they are actually _called_.  By
the time they are called (after `window.onload`), their symbols are already
defined since all javascript code has been evaluated.

## Further Reading

- [Frustrations with namespaces in Clojure](http://programming-puzzler.blogspot.com/2013/12/frustrations-with-namespaces-in-clojure.html)
