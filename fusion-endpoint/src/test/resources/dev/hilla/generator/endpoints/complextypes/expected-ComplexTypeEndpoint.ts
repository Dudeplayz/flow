/**
 * This module is generated from ComplexTypeEndpoint.java
 * All changes to this file are overridden. Please consider to make changes in the corresponding Java file if necessary.
 * @module ComplexTypeEndpoint
 */

// @ts-ignore
import client, { subscribe, Subscription } from './connect-client.default';
import type ComplexTypeModel from './dev/hilla/generator/endpoints/complextypes/ComplexTypeEndpoint/ComplexTypeModel';

function _getComplexTypeModel(
  data: Array<Record<string, string | undefined> | undefined> | undefined
): Promise<ComplexTypeModel | undefined> {
  return client.call('ComplexTypeEndpoint', 'getComplexTypeModel', {data});
}

export {
  _getComplexTypeModel as getComplexTypeModel,
};