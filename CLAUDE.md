# power-plugin — Project Memory

## Anti-Vibe-Coding Design Standards

Apply these rules to all UI work in this project. Every spacing value, color, radius, weight, and animation must reference a defined design system. Inconsistency signals vibe-coding more than any single element.

### Colors & Visual
- No default purple gradients unless brand-appropriate
- No sparkles or emojis in hero headings
- No generic glowing hover effects

### Typography
- Consistent weight hierarchy — avoid oversized headings paired with ultra-thin body text
- Uniform line-height and paragraph spacing throughout
- Define a type scale and stick to it; never deviate ad hoc

### Layout & Components
- Identical component placement across pages
- Define 2–3 border-radius values max; use them consistently
- Hover states: subtle lift only (2–4px max)
- Icon sizing must be proportional relative to surrounding text
- Remove non-functional social icons

### Animations & Interactions
- Use easing curves (cubic-bezier) — no linear or default ease
- Stagger timing intentionally, not randomly
- Every animation must serve a purpose; decorative motion is cut

### UX Behaviors
- Loading states required for all async actions
- Progress indicators on buttons during submission
- All toggles, carousels, and interactions must be functional — no fakes
- Skeleton screens for data-heavy sections

### Copywriting
- No em-dash overuse
- No vague hero phrases: "Launch faster", "Build your dreams", "Create without limits"
- No fake testimonials
- No generic AI faces or placeholder names like "Sarah Chen"

### Core Principle
**Design system first.** Define tokens for every spacing value, color, radius, font weight, and animation before writing component code. Reference the token, never hardcode.
