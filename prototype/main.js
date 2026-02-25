// ===========================
// Todo App - Main Application Logic
// ===========================

const STORAGE_KEY = 'todo-app-items';
const THEME_KEY = 'todo-app-theme';

// ===========================
// State
// ===========================

let todos = [];
let currentFilter = 'all';

// ===========================
// DOM References
// ===========================

const todoForm = document.getElementById('todo-form');
const todoInput = document.getElementById('todo-input');
const todoList = document.getElementById('todo-list');
const todoCount = document.getElementById('todo-count');
const clearCompletedBtn = document.getElementById('clear-completed');
const themeToggle = document.getElementById('theme-toggle');
const filterBtns = document.querySelectorAll('.filter-btn');

// ===========================
// Persistence (localStorage)
// ===========================

/** Load todos from localStorage */
function loadTodos() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    todos = stored ? JSON.parse(stored) : [];
  } catch {
    todos = [];
  }
}

/** Save todos to localStorage */
function saveTodos() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(todos));
}

// ===========================
// Theme Management
// ===========================

/** Initialize theme from localStorage or system preference */
function initTheme() {
  const savedTheme = localStorage.getItem(THEME_KEY);
  if (savedTheme) {
    document.documentElement.setAttribute('data-theme', savedTheme);
  } else {
    // Respect system preference
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (prefersDark) {
      document.documentElement.setAttribute('data-theme', 'dark');
    }
  }
}

/** Toggle between light and dark theme */
function toggleTheme() {
  const current = document.documentElement.getAttribute('data-theme');
  const next = current === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem(THEME_KEY, next);
}

// ===========================
// Todo CRUD Operations
// ===========================

/** Generate a unique ID for a new todo */
function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/** Add a new todo item */
function addTodo(text) {
  const trimmed = text.trim();
  if (!trimmed) return;

  todos.push({
    id: generateId(),
    text: trimmed,
    completed: false,
    createdAt: Date.now(),
  });

  saveTodos();
  render();
}

/** Toggle the completed state of a todo */
function toggleTodo(id) {
  const todo = todos.find((t) => t.id === id);
  if (todo) {
    todo.completed = !todo.completed;
    saveTodos();
    render();
  }
}

/** Delete a todo by ID */
function deleteTodo(id) {
  todos = todos.filter((t) => t.id !== id);
  saveTodos();
  render();
}

/** Remove all completed todos */
function clearCompleted() {
  todos = todos.filter((t) => !t.completed);
  saveTodos();
  render();
}

// ===========================
// Filtering
// ===========================

/** Get todos matching the current filter */
function getFilteredTodos() {
  switch (currentFilter) {
    case 'open':
      return todos.filter((t) => !t.completed);
    case 'completed':
      return todos.filter((t) => t.completed);
    default:
      return todos;
  }
}

/** Set the active filter and re-render */
function setFilter(filter) {
  currentFilter = filter;

  // Update active state on filter buttons
  filterBtns.forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.filter === filter);
  });

  render();
}

// ===========================
// Rendering
// ===========================

/** Render the todo list to the DOM */
function render() {
  const filtered = getFilteredTodos();
  const openCount = todos.filter((t) => !t.completed).length;
  const completedCount = todos.filter((t) => t.completed).length;

  // Update item count
  todoCount.textContent = `${openCount} item${openCount !== 1 ? 's' : ''} left`;

  // Show/hide clear completed button
  clearCompletedBtn.style.display = completedCount > 0 ? 'inline' : 'none';

  // Clear the list
  todoList.innerHTML = '';

  // Show empty state if no filtered todos
  if (filtered.length === 0) {
    const emptyEl = document.createElement('li');
    emptyEl.className = 'empty-state';
    emptyEl.textContent =
      currentFilter === 'all'
        ? 'No todos yet. Add one above!'
        : currentFilter === 'open'
          ? 'No open todos. Great job!'
          : 'No completed todos yet.';
    todoList.appendChild(emptyEl);
    return;
  }

  // Render each todo item
  filtered.forEach((todo) => {
    const li = document.createElement('li');
    li.className = `todo-item${todo.completed ? ' completed' : ''}`;

    // Checkbox
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'todo-checkbox';
    checkbox.checked = todo.completed;
    checkbox.setAttribute('aria-label', `Mark "${todo.text}" as ${todo.completed ? 'incomplete' : 'complete'}`);
    checkbox.addEventListener('change', () => toggleTodo(todo.id));

    // Text span
    const textSpan = document.createElement('span');
    textSpan.className = 'todo-text';
    textSpan.textContent = todo.text;

    // Delete button
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'todo-delete';
    deleteBtn.innerHTML = '&times;';
    deleteBtn.setAttribute('aria-label', `Delete "${todo.text}"`);
    deleteBtn.addEventListener('click', () => deleteTodo(todo.id));

    li.appendChild(checkbox);
    li.appendChild(textSpan);
    li.appendChild(deleteBtn);
    todoList.appendChild(li);
  });
}

// ===========================
// Event Listeners
// ===========================

// Handle form submission to add new todo
todoForm.addEventListener('submit', (e) => {
  e.preventDefault();
  addTodo(todoInput.value);
  todoInput.value = '';
  todoInput.focus();
});

// Handle clear completed button
clearCompletedBtn.addEventListener('click', clearCompleted);

// Handle theme toggle
themeToggle.addEventListener('click', toggleTheme);

// Handle filter button clicks
filterBtns.forEach((btn) => {
  btn.addEventListener('click', () => setFilter(btn.dataset.filter));
});

// ===========================
// Initialization
// ===========================

initTheme();
loadTodos();
render();
