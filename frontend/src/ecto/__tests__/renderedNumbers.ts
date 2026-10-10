/**
 * Test-side reading of a rendered number, independent of the runner's default locale.
 *
 * The screen formats whole numbers with `Number.prototype.toLocaleString()`, so the separator it
 * prints is whatever this machine's ICU default happens to be: `14,134` on the `en-US` runner CI
 * uses, `14.134` on a `de-DE` workstation, and `14134` with no separator at all in a locale that
 * does not group four-digit numbers. An expectation built with `toLocaleString()` as well would
 * state the same locale back to itself instead of naming the value under test, so an expectation
 * compares the digits and leaves the grouping to the browser.
 */

/**
 * `text` with digit grouping removed, so an expectation can name the integer the page was given.
 *
 * Only a separator standing between a digit and a *full* group of three is dropped, which leaves a
 * decimal fraction — the two-digit Luck-per-Ecto figures — untouched. Groups of other sizes (the
 * Indian grouping of `en-IN`, say) are out of scope: no expectation here depends on one.
 */
export function withoutDigitGrouping(text: string): string {
  return text.replace(/(?<=\d)[.,'   ](?=\d{3}(?!\d))/g, '')
}
