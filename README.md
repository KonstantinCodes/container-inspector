# Container Inspector for Symfony

Visualize and explore your Symfony Dependency Injection Container with an interactive graph interface.

## Key Features

- **Interactive Graph Visualization** - See your entire service architecture at a glance with an automatic hierarchical layout
- **Smart Search & Navigation** - Find services by ID or class name with instant highlighting and one-click navigation to PHP source code
- **Dependency Analysis** - Explore service dependencies and usages interactively by expanding nodes to reveal connections
- **Multiple Configuration Profiles** - Manage different container configurations
- **Editor Synchronization** - Optional link-with-editor mode to automatically select services as you navigate your codebase
- **Performance Optimized** - Handles large containers smoothly with intelligent filtering and minification options

## How It Works

Container Inspector parses your compiled Symfony container XML file (typically found in `var/cache/dev/`) and renders it as an interactive graph. Simply configure the path to your container XML, and start exploring your service architecture visually.

Perfect for understanding complex service relationships, debugging DI issues, and onboarding new team members to your Symfony application architecture.

## Symfony

This plugin is not affiliated with, approved, sponsored or endorsed by the Symfony project. Symfony is a trademark of Symfony SAS.