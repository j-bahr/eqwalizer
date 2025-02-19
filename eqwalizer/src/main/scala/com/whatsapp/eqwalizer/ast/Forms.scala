/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.ast

import com.ericsson.otp.erlang._
import com.whatsapp.eqwalizer.ast.Exprs.{Clause, Expr}
import com.whatsapp.eqwalizer.ast.ExternalTypes._
import com.whatsapp.eqwalizer.ast.InvalidDiagnostics.Invalid
import com.whatsapp.eqwalizer.ast.Types._
import com.whatsapp.eqwalizer.ast.stub.DbApi
import com.whatsapp.eqwalizer.tc.TcDiagnostics.{BehaviourError, TypeError}
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.whatsapp.eqwalizer.io.Ipc

import scala.collection.immutable.TreeSeqMap

object Forms {

  case class Module(name: String)(val pos: Pos) extends ExternalForm with InternalForm
  case class CompileExportAll()(val pos: Pos) extends ExternalForm
  case class Export(funs: List[Id])(val pos: Pos) extends ExternalForm with InternalForm
  case class Import(module: String, funs: List[Id])(val pos: Pos) extends ExternalForm with InternalForm
  case class ExportType(types: List[Id])(val pos: Pos) extends ExternalForm with InternalForm
  case class FunDecl(id: Id, clauses: List[Clause])(val pos: Pos) extends ExternalForm with InternalForm
  case class File(file: String, start: Int)(val pos: Pos) extends ExternalForm with InternalForm
  case class Fixme(comment: TextRange, suppression: TextRange, isIgnore: Boolean)
  case class ElpMetadata(fixmes: List[Fixme])(val pos: Pos) extends ExternalForm with InternalForm
  case class Behaviour(name: String)(val pos: Pos) extends ExternalForm with InternalForm
  case class EqwalizerNowarnFunction(id: Id)(val pos: Pos) extends ExternalForm with InternalForm
  case class EqwalizerUnlimitedRefinement(id: Id)(val pos: Pos) extends ExternalForm with InternalForm

  /** used for analyses only, should not affect the behavior of the type checker
   */
  case class TypingAttribute(names: List[String])(val pos: Pos) extends ExternalForm

  sealed trait Form

  sealed trait ExternalForm extends Form { val pos: Pos }
  case class ExternalTypeDecl(id: Id, params: List[String], body: ExtType, file: Option[String])(val pos: Pos)
      extends ExternalForm
  case class ExternalOpaqueDecl(id: Id, params: List[String], body: ExtType, file: Option[String])(val pos: Pos)
      extends ExternalForm
  case class ExternalFunSpec(id: Id, types: List[ConstrainedFunType])(val pos: Pos) extends ExternalForm
  case class ExternalCallback(id: Id, types: List[ConstrainedFunType])(val pos: Pos) extends ExternalForm
  case class ExternalOptionalCallbacks(ids: List[Id])(val pos: Pos) extends ExternalForm
  case class ExternalRecDecl(name: String, fields: List[ExternalRecField], file: Option[String])(val pos: Pos)
      extends ExternalForm
  case class ExternalRecField(name: String, tp: Option[ExtType], defaultValue: Option[Expr])

  sealed trait InternalForm extends Form { val pos: Pos }
  case class FunSpec(id: Id, ty: FunType)(val pos: Pos) extends InternalForm
  case class OverloadedFunSpec(id: Id, tys: List[FunType])(val pos: Pos) extends InternalForm

  // empty tys list is used to represent callback with an invalid type
  case class Callback(id: Id, tys: List[FunType])(val pos: Pos) extends InternalForm
  case class RecDecl(name: String, fields: List[RecField], refinable: Boolean, file: Option[String])(val pos: Pos)
      extends InternalForm
  case class RecDeclTyped(
      name: String,
      fields: TreeSeqMap[String, RecFieldTyped],
      refinable: Boolean,
      file: Option[String],
  )
  case class RecField(name: String, tp: Option[Type], defaultValue: Option[Expr], refinable: Boolean)
  case class RecFieldTyped(name: String, tp: Type, defaultValue: Option[Expr], refinable: Boolean)
  case class OpaqueTypeDecl(id: Id, file: Option[String])(val pos: Pos) extends InternalForm
  case class TypeDecl(id: Id, params: List[VarType], body: Type, file: Option[String])(val pos: Pos)
      extends InternalForm

  sealed trait InvalidForm extends InternalForm {
    val te: TypeError
  }
  case class InvalidTypeDecl(id: Id, te: Invalid)(val pos: Pos) extends InvalidForm
  case class InvalidFunSpec(id: Id, te: Invalid)(val pos: Pos) extends InvalidForm
  case class InvalidRecDecl(name: String, te: Invalid)(val pos: Pos) extends InvalidForm
  case class InvalidConvertTypeInRecDecl(name: String, te: Invalid)(val pos: Pos) extends InvalidForm

  case class NoSpecFuncDecl(id: Id)(val pos: Pos) extends InternalForm
  case class FuncDecl(id: Id, errors: List[TypeError])(val pos: Pos) extends InternalForm
  case class MisBehaviour(te: BehaviourError)(val pos: Pos) extends InternalForm

  def load(astStorage: DbApi.AstStorage): List[ExternalForm] = {
    import com.whatsapp.eqwalizer.io.AstLoader
    import com.whatsapp.eqwalizer.io.EData.EList

    astStorage match {
      case storage: DbApi.AstBeamEtfStorage =>
        val Some(EList(rawForms, None)) = AstLoader.loadAbstractForms(storage)
        val isBeam = storage match {
          case _: DbApi.AstBeam => true
          case _                => false
        }
        val noAutoImport = rawForms.flatMap(new ConvertAst(isBeam).extractNoAutoImport).flatten.toSet
        rawForms.flatMap(new ConvertAst(isBeam, noAutoImport).convertForm)
      case DbApi.AstJsonIpc(module) =>
        val bytes = Ipc.getAstBytes(module, Ipc.ConvertedForms).get
        readFromArray[List[ExternalForm]](bytes)
    }
  }

  private val functionAtom = new OtpErlangAtom("function")
  def isFunForm(o: OtpErlangObject): Boolean =
    o.asInstanceOf[OtpErlangTuple].elementAt(0).equals(functionAtom)

  implicit val codec: JsonValueCodec[List[ExternalForm]] = JsonCodecMaker.make(
    CodecMakerConfig.withAllowRecursiveTypes(true).withDiscriminatorFieldName(None).withFieldNameMapper {
      case "pos"                     => "location"
      case "mod"                     => "module"
      case s if !s.charAt(0).isUpper => JsonCodecMaker.enforce_snake_case(s)
      case s                         => s
    }
  )
}
