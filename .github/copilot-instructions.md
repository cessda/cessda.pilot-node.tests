---
name: Project Coding Standards
description: Strict coding guidelines for our TypeScript backend
---

## Code Formatting & Style

- Use UK English for naming classes, method, variables etc
- Follow the established and documented Java coding conventions
- Always use 2 spaces for indentation. Never use tabs.
- Prefer arrow functions `const buy = () => {}` over standard
  function declarations.
- Use single quotes for strings, except when avoiding escaping.
- Restrict line lengths to 80 characters where possible.

## Architecture & State

- Always write pure functions with explicit TypeScript types.
- Do not use external libraries for date handling; use the native Date object.
- Implement strict error boundaries with try/catch blocks for all async
  operations.

## Class Documentation

- Use UK English.
- Add Javadoc to all classes and methods. Do not use HTML markup.
- Do not add inline comments explaining *what* the code does. Only add
  inline comments explaining *why*.

## ReadMe files

- Use UK English and remember that a heading must have a blank line after it
  and a list must have a blank line before it.
- Don't use emphasis (bold or italics) as headings, use # characters.
- Only one blank line between the end of a section and the next heading.
- All fenced code blocks must have a language/type specified, even if it is
  only text.
- Restrict line lengths to 80 characters where possible.
