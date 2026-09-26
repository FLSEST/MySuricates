import { describe, expect, it } from 'vitest';
import { readContextSchema } from './contextSchema';

describe('readContextSchema', () => {
  it('returns empty info when no schema present', () => {
    const info = readContextSchema({ metadata: undefined });
    expect(info.optional).toEqual([]);
    expect(info.descriptions).toEqual({});
    expect(info.placeholders).toEqual({});
  });

  it('extracts optional variables', () => {
    const info = readContextSchema({
      metadata: {
        contextSchema: {
          source_url: { required: false },
          source_file: { required: false },
          target: { required: true },
        },
      },
    });
    expect(info.optional.sort()).toEqual(['source_file', 'source_url']);
  });

  it('treats unspecified required as required (not in optional list)', () => {
    const info = readContextSchema({
      metadata: {
        contextSchema: {
          foo: { description: 'bar' },
        },
      },
    });
    expect(info.optional).toEqual([]);
    expect(info.descriptions).toEqual({ foo: 'bar' });
  });

  it('collects descriptions and placeholders', () => {
    const info = readContextSchema({
      metadata: {
        contextSchema: {
          name: { description: 'Your full name', placeholder: 'e.g. Alex' },
        },
      },
    });
    expect(info.descriptions).toEqual({ name: 'Your full name' });
    expect(info.placeholders).toEqual({ name: 'e.g. Alex' });
  });
});
