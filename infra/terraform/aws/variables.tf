variable "region" {
  type    = string
  default = "ap-south-1"
}
variable "name" {
  type    = string
  default = "fleettruth"
}
variable "private_subnet_ids" {
  type        = list(string)
  description = "Existing private subnets spanning at least three availability zones, with required endpoints or NAT."
  validation {
    condition     = length(var.private_subnet_ids) >= 3
    error_message = "Provide at least three private subnets."
  }
}
variable "operator_role_arn" {
  type        = string
  description = "Existing human/CI operator role granted cluster administration. Review this high-privilege grant before apply."
}
variable "kubernetes_version" {
  type        = string
  description = "Choose a currently supported EKS version for the selected region. No stale version default."
}
variable "node_instance_type" {
  type    = string
  default = "m6i.large"
}
