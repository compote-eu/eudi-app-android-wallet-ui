/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.commonfeature.util

/**
 * The two claim-key predicates the shared claim-tree builder needs, lifted out of `DocumentHelper`.
 *
 * They were only *co-located* with that file's Android code, never coupled to it: both are pure
 * `String` tests over [DocumentJsonKeys]. Everything else in `DocumentHelper` needs an
 * `IssuedDocument` or a `ResourceProvider` and stays in :common-feature. The package is unchanged, so
 * existing `import eu.europa.ec.commonfeature.util.keyIs*` call sites resolve as before.
 */
fun keyIsUserImage(key: String): Boolean {
    val listOfUserImageKeys = DocumentJsonKeys.BASE64_USER_IMAGE_KEYS
    return listOfUserImageKeys.contains(key)
}

fun keyIsSignature(key: String): Boolean {
    return key == DocumentJsonKeys.SIGNATURE
}
