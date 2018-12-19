/*example(Package):19*/package example

/*example.StructuralTypes(Module):19*/object StructuralTypes {
  /*example.StructuralTypes.User(Field):7*/type User = {
    def name: String
    def age: Int
  }

  /*example.StructuralTypes.user(Constant):9*/val user = null.asInstanceOf[User]
  user.name
  user.age

  /*example.StructuralTypes.V(Constant):17*/val V: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
