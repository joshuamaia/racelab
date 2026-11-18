import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** Aura customised to the RaceLab palette: blue/cyan accents on navy/graphite surfaces. */
export const RaceLabPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#eff6ff',
      100: '#dbeafe',
      200: '#bfdbfe',
      300: '#93c5fd',
      400: '#60a5fa',
      500: '#3b82f6',
      600: '#2563eb',
      700: '#1d4ed8',
      800: '#1e40af',
      900: '#1e3a8a',
      950: '#172554',
    },
    colorScheme: {
      light: {
        surface: {
          0: '#ffffff',
          50: '#f6f8fc',
          100: '#eef2f8',
          200: '#e1e7f0',
          300: '#cbd4e1',
          400: '#94a3b8',
          500: '#64748b',
          600: '#475569',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
          950: '#020617',
        },
      },
      dark: {
        surface: {
          0: '#ffffff',
          50: '#e6ebf5',
          100: '#c9d3e6',
          200: '#a3b1cc',
          300: '#7d8cab',
          400: '#5b6a8a',
          500: '#3d4b69',
          600: '#2a3754',
          700: '#1b2740',
          800: '#151f35',
          900: '#111a2e',
          950: '#0b1220',
        },
        primary: {
          color: '{primary.400}',
          contrastColor: '{surface.950}',
          hoverColor: '{primary.300}',
          activeColor: '{primary.200}',
        },
      },
    },
  },
});
